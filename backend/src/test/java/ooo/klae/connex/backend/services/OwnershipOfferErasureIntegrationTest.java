package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.support.OwnershipOfferTestFixtures;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;

/** Explicit party and closure-actor erasure and destination cleanup preserve the ledger lock order. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OwnershipOfferErasureIntegrationTest extends AbstractServiceTest {
    @Autowired private WorkspaceService workspaces;
    @Autowired private OrgMemberService organizations;
    @Autowired private OrgMemberMapper orgMembers;
    @MockitoSpyBean private OwnershipOfferMapper ledger;
    @MockitoSpyBean private AuditService audit;
    @MockitoSpyBean private ooo.klae.connex.backend.mappers.UserMapper users;
    @Autowired private OwnershipOfferService offers;
    @Autowired private OwnershipOfferDeliveryService delivery;
    @Autowired private OwnershipOfferAccountCleanupService cleanup;
    @Autowired private UserDeletionTransaction deletion;
    @Autowired private UserAccountCatalogOffboardingService catalogOffboarding;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<Integer> accounts = new ArrayList<>();
    private final List<Integer> extraWorkspaces = new ArrayList<>();
    @Override protected User newUser() { User user = super.newUser(); accounts.add(user.getId()); return user; }
    @BeforeEach void freshOwner() { OwnershipOfferTestFixtures.stepUpCurrent(jdbc, currentUser); }
    @AfterEach void clean() {
        reset(users, ledger, audit);
        extraWorkspaces.forEach(id -> jdbc.update("DELETE FROM workspace WHERE id = ?", id));
        accounts.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id)); accounts.clear(); extraWorkspaces.clear();
    }
    private long create(User target) { return Long.parseLong(workspaces.changeMemberRole(workspace.getId(), currentUser.getId(), target.getId(), "owner").offer().id()); }
    private void erase(User user) {
        authenticateAs(user, workspace.getId());
        String token = "ownership-erasure-" + unique();
        deletion.reserve(user.getId(), token);
        cleanup.eraseAfterCatalogs(user.getId());
        deletion.delete(user.getId(), token);
        assertNull(userMapper.getUserById(user.getId()));
    }

    @Test void offereeErasureRemovesOffersAndNoticeHistoryBeforeFinalDeletion() {
        User target = newUser(); long id = create(target); delivery.deliver(ledger.findById(id));
        erase(target);
        assertNull(ledger.findById(id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE dedupe_key = ?", Integer.class, "ownership.offer:" + id));
        assertTrue(ledger.discoverAccount(target.getId()).isEmpty());
    }
    @Test void guardedCatalogErasureToleratesAnAlreadyDeletedNoticeAndRetainsTheReservation() {
        User target = newUser();
        long id = create(target);
        delivery.deliver(ledger.findById(id));
        String token = "ownership-erasure-" + unique();
        deletion.reserve(target.getId(), token);
        assertTrue(userMapper.isAccountDeletionReserved(target.getId()));
        assertThrows(ResourceNotFoundException.class, () -> create(target));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE dedupe_key = ?",
            Integer.class, "ownership.offer:" + id));
        var realLedger = sql.getMapper(OwnershipOfferMapper.class);
        doAnswer(call -> {
            assertTrue(userMapper.isAccountDeletionReserved(target.getId()));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE dedupe_key = ?",
                Integer.class, "ownership.offer:" + id));
            return realLedger.close(call.getArgument(0), call.getArgument(1),
                call.getArgument(2), call.getArgument(3));
        }).when(ledger).close(eq(id), eq("INVALIDATED"), isNull(), eq("account_erasure"));

        catalogOffboarding.eraseReferences(target.getId());

        assertNull(ledger.findById(id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE dedupe_key = ?",
            Integer.class, "ownership.offer:" + id));
        assertTrue(userMapper.isAccountDeletionReserved(target.getId()));
        assertNotNull(userMapper.getUserById(target.getId()));
        deletion.delete(target.getId(), token);
        assertNull(userMapper.getUserById(target.getId()));
    }

    @Test void offerorErasureClosesAndErasesConsentWithACoOwnerStillPresent() {
        User target = newUser(); User coOwner = newUser(); workspaceMapper.updateMemberRole(workspace.getId(), coOwner.getId(), "owner");
        long id = create(target); delivery.deliver(ledger.findById(id)); erase(currentUser);
        assertNull(ledger.findById(id));
        assertEquals("member", workspaceMapper.getRole(workspace.getId(), target.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE dedupe_key = ?", Integer.class, "ownership.offer:" + id));
    }
    @Test void erasingAThirdOwnerExplicitlyNullsAttributionWithoutDeletingTheOtherPartiesOffer() {
        User target = newUser(); User third = newUser(); workspaceMapper.updateMemberRole(workspace.getId(), third.getId(), "owner");
        long id = create(target); delivery.deliver(ledger.findById(id));
        authenticateAs(third, workspace.getId()); OwnershipOfferTestFixtures.stepUpCurrent(jdbc, third);
        offers.revoke("WORKSPACE", workspace.getId(), id, third.getId());
        assertEquals(third.getId(), ledger.findById(id).getClosedByUserId());
        clearInvocations(ledger, audit, users);
        erase(third);
        var order = inOrder(ledger, audit, users);
        order.verify(ledger).clearClosureActor(id, third.getId());
        order.verify(audit).recordScoped(eq("user.delete"), anyString(), eq(third.getId()), any(), any(), any(), any(), any());
        order.verify(users).delete(third.getId());
        assertEquals("REVOKED", ledger.findById(id).getState()); assertNull(ledger.findById(id).getClosedByUserId());
        assertTrue(ledger.discoverAccount(third.getId()).isEmpty());
    }
    @Test void destinationDeparturePreservesQualifiedOrgConsentAndClearsItsNoticePointer() {
        int orgId = workspaceMapper.getOrgId(workspace.getId()); orgMembers.addMember(orgId, currentUser.getId(), "owner");
        User target = newUser(); orgMembers.addMember(orgId, target.getId(), "admin");
        var offered = organizations.setMember(orgId, currentUser.getId(), target.getId(), "owner"); long id = Long.parseLong(offered.offer().id());
        delivery.deliver(ledger.findById(id));
        workspaces.removeMember(workspace.getId(), currentUser.getId(), target.getId());
        assertEquals("OPEN", ledger.findById(id).getState()); assertNull(ledger.findById(id).getDeliveryWorkspaceId());
        authenticateAs(target, workspace.getId()); OwnershipOfferTestFixtures.stepUpCurrent(jdbc, target);
        assertEquals(1, offers.listForSelf(target.getId()).size());
        assertEquals("ACCEPTED", offers.accept(id, target.getId(), false).offer().state());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE dedupe_key = ?", Integer.class, "ownership.offer:" + id));
    }
    @Test void finalDeletionRefusesAnUncleanedOpenPartyReference() {
        User target = newUser(); create(target); authenticateAs(target, workspace.getId());
        String token = "ownership-erasure-" + unique(); deletion.reserve(target.getId(), token);
        assertThrows(ooo.klae.connex.backend.exceptions.ConflictException.class, () -> deletion.delete(target.getId(), token));
        assertNotNull(userMapper.getUserById(target.getId()));
        cleanup.eraseAfterCatalogs(target.getId()); deletion.delete(target.getId(), token);
        assertNull(userMapper.getUserById(target.getId()));
    }

    @Test void finalDeletionRechecksItsLeaseAfterAnObservedAccountLockWait() throws Exception {
        User target = newUser(); create(target);
        String token = "ownership-erasure-" + unique(); deletion.reserve(target.getId(), token);
        var worker = Executors.newSingleThreadExecutor();
        var connection = new AtomicLong(); var known = new CountDownLatch(1);
        var deleting = new ThreadLocal<Boolean>();
        var real = sql.getMapper(ooo.klae.connex.backend.mappers.UserMapper.class);
        doAnswer(call -> {
            if (Boolean.TRUE.equals(deleting.get())) {
                connection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)); known.countDown();
            }
            return real.lockById(call.getArgument(0));
        }).when(users).lockById(target.getId());
        try {
            var refused = new TransactionTemplate(transactionManager).execute(status -> {
                users.lockById(target.getId());
                jdbc.update("UPDATE app_user SET account_deletion_reserved_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR), account_deletion_reservation_until = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id = ?", target.getId());
                var future = worker.submit(() -> {
                    deleting.set(true);
                    try { return assertThrows(ooo.klae.connex.backend.exceptions.ConflictException.class,
                        () -> deletion.delete(target.getId(), token)); }
                    finally { deleting.remove(); }
                });
                try { assertTrue(known.await(30, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, connection.get(), "app_user", Integer.toString(target.getId()));
                return future;
            });
            assertNotNull(refused);
            assertEquals("Account deletion was not reserved", refused.get(30, TimeUnit.SECONDS).getMessage());
            assertNotNull(userMapper.getUserById(target.getId()));
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS)); }
    }

    @Test void offerorErasureClosesNoticesWhileTheOffereeAccountRemainsExclusivelyLocked() throws Exception {
        User target = newUser(); User coOwner = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), coOwner.getId(), "owner");
        long id = create(target); delivery.deliver(ledger.findById(id));
        long version = jdbc.queryForObject("SELECT state_version FROM notification_recipient_state WHERE recipient_id = ?", Long.class, target.getId());
        var reached = new CountDownLatch(1); var connection = new AtomicLong();
        var real = sql.getMapper(OwnershipOfferMapper.class);
        doAnswer(call -> {
            connection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)); reached.countDown();
            return real.bumpExistingNoticeStateVersion(call.getArgument(0));
        }).when(ledger).bumpExistingNoticeStateVersion(target.getId());
        var started = new CountDownLatch(1);
        var realUsers = sql.getMapper(ooo.klae.connex.backend.mappers.UserMapper.class);
        doAnswer(call -> {
            connection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)); started.countDown();
            return realUsers.lockById(call.getArgument(0));
        }).when(users).lockById(currentUser.getId());
        var worker = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                users.lockById(target.getId());
                var erased = worker.submit(() -> cleanup.eraseAfterCatalogs(currentUser.getId()));
                try {
                    assertTrue(started.await(30, TimeUnit.SECONDS));
                    if (!reached.await(10, TimeUnit.SECONDS)) {
                        if (erased.isDone()) { erased.get(); }
                        throw new AssertionError("Erasure blocked before notice counter: " + jdbc.queryForList(
                            "SELECT l.OBJECT_NAME, l.INDEX_NAME, l.LOCK_MODE, l.LOCK_DATA FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID JOIN performance_schema.threads t ON t.THREAD_ID = l.THREAD_ID WHERE t.PROCESSLIST_ID = ?", connection.get()));
                    }
                    erased.get(5, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException timeout) {
                    MySqlLockWaitProbe.awaitSharedRecordLock(jdbc, connection.get(), "app_user", Integer.toString(target.getId()));
                    throw new AssertionError("Offer erasure took a counterpart account lock after roots", timeout);
                } catch (Exception failure) { throw new IllegalStateException(failure); }
                assertNull(ledger.findById(id));
                assertEquals(version + 1, jdbc.queryForObject("SELECT state_version FROM notification_recipient_state WHERE recipient_id = ?", Long.class, target.getId()));
            });
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS)); }
    }

    @Test void rootHeldClosureFinishesWhileOffereeErasureHoldsItsAccountAndWaitsForThatRoot() throws Exception {
        User target = newUser(); User coOwner = newUser();
        workspaceMapper.updateMemberRole(workspace.getId(), coOwner.getId(), "owner");
        long id = create(target); delivery.deliver(ledger.findById(id));
        var rootHeld = new CountDownLatch(1); var releaseClosure = new CountDownLatch(1);
        var accountHeld = new CountDownLatch(1); var offereeConnection = new AtomicLong();
        var realLedger = sql.getMapper(OwnershipOfferMapper.class);
        doAnswer(call -> {
            rootHeld.countDown();
            assertTrue(releaseClosure.await(30, TimeUnit.SECONDS));
            return realLedger.close(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
        }).when(ledger).close(eq(id), eq("INVALIDATED"), isNull(), eq("account_erasure"));
        var realUsers = sql.getMapper(ooo.klae.connex.backend.mappers.UserMapper.class);
        doAnswer(call -> {
            offereeConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            Integer locked = realUsers.lockById(call.getArgument(0));
            accountHeld.countDown();
            return locked;
        }).when(users).lockById(target.getId());
        var workers = Executors.newFixedThreadPool(2);
        try {
            var offerorErased = workers.submit(() -> cleanup.eraseAfterCatalogs(currentUser.getId()));
            assertTrue(rootHeld.await(30, TimeUnit.SECONDS));
            var offereeErased = workers.submit(() -> cleanup.eraseAfterCatalogs(target.getId()));
            assertTrue(accountHeld.await(30, TimeUnit.SECONDS));
            MySqlLockWaitProbe.awaitSharedRecordLock(jdbc, offereeConnection.get(),
                "workspace", Integer.toString(workspace.getId()));
            releaseClosure.countDown();
            offerorErased.get(10, TimeUnit.SECONDS);
            offereeErased.get(10, TimeUnit.SECONDS);
            assertNull(ledger.findById(id));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE dedupe_key = ?",
                Integer.class, "ownership.offer:" + id));
            assertTrue(ledger.discoverAccount(target.getId()).isEmpty());
            assertTrue(ledger.discoverAccount(currentUser.getId()).isEmpty());
        } finally {
            releaseClosure.countDown(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS));
        }
    }
}
