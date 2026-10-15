package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ooo.klae.connex.backend.beans.OwnershipOffer;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import ooo.klae.connex.backend.support.OwnershipOfferTestFixtures;

/** Holds production locks after the rechecks and proves real waits before either commit order. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OwnershipOfferConcurrencyIntegrationTest extends AbstractServiceTest {
    private static final ThreadLocal<Probe> PROBE = new ThreadLocal<>();
    private record Probe(AtomicLong connection, CountDownLatch known) {}
    @Autowired private WorkspaceService workspaces;
    @Autowired private OwnershipOfferService offers;
    @Autowired private OwnershipOfferDeliveryService delivery;
    @Autowired private OwnershipOfferAccountCleanupService cleanup;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private OwnershipOfferMapper ledger;
    @MockitoSpyBean private UserMapper users;
    private final List<Integer> accounts = new ArrayList<>();
    private final Map<Integer, Integer> credentials = new HashMap<>();
    private User target;
    private User coOwner;
    @Autowired private OrgMemberService organizations;
    @Autowired private ooo.klae.connex.backend.mappers.OrgMemberMapper orgMembers;
    private long offerId;

    @Override protected User newUser() { User user = super.newUser(); accounts.add(user.getId()); return user; }
    @BeforeEach void fixtureAndProbes() {
        OwnershipOfferTestFixtures.restartExpiryTraversal(offers);
        target = newUser(); coOwner = newUser(); workspaceMapper.updateMemberRole(workspace.getId(), coOwner.getId(), "owner");
        for (User user : List.of(currentUser, target, coOwner)) { credentials.put(user.getId(), OwnershipOfferTestFixtures.passkey(jdbc, user)); }
        as(currentUser);
        offerId = Long.parseLong(workspaces.changeMemberRole(workspace.getId(), currentUser.getId(), target.getId(), "owner").offer().id());
        UserMapper real = sql.getMapper(UserMapper.class);
        doAnswer(call -> { recordConnection(); return real.lockById(call.getArgument(0)); }).when(users).lockById(anyInt());
        doAnswer(call -> { recordConnection(); return real.lockByIdForShare(call.getArgument(0)); }).when(users).lockByIdForShare(anyInt());
    }
    @AfterEach void clean() {
        reset(ledger, users);
        accounts.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id)); accounts.clear(); credentials.clear();
    }
    private void as(User user) {
        authenticateAs(user, workspace.getId());
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        OwnershipOfferTestFixtures.stepUp(jdbc, user, request.getSession(), credentials.get(user.getId()));
    }
    private void recordConnection() {
        Probe probe = PROBE.get();
        if (probe != null && probe.connection().get() == 0) {
            probe.connection().set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)); probe.known().countDown();
        }
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(30, TimeUnit.SECONDS), "paused worker was not released"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    private void close(String action) {
        switch (action) {
            case "accept" -> { as(target); offers.accept(offerId, target.getId(), false); }
            case "decline" -> { as(target); offers.decline(offerId, target.getId()); }
            case "revoke" -> { as(currentUser); offers.revoke("WORKSPACE", workspace.getId(), offerId, currentUser.getId()); }
            case "expiry" -> offers.expire();
            case "offeror-erasure" -> cleanup.eraseAfterCatalogs(currentUser.getId());
            case "offeree-erasure" -> cleanup.eraseAfterCatalogs(target.getId());
            default -> throw new IllegalArgumentException(action);
        }
    }
    private void expireBeforeWorkerIfNeeded(String action) {
        if (action.equals("expiry")) {
            jdbc.update("UPDATE ownership_offer SET created_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 8 DAY), expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY) WHERE id = ?", offerId);
        }
    }
    private void assertNeverOpenNotice() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE dedupe_key = ? AND resolved_at IS NULL", Integer.class, "ownership.offer:" + offerId));
        OwnershipOffer offer = ledger.findById(offerId);
        assertTrue(offer == null || !"OPEN".equals(offer.getState()));
    }

    @ParameterizedTest @ValueSource(strings = {"WORKSPACE:accept", "WORKSPACE:demote", "ORGANIZATION:accept", "ORGANIZATION:demote"})
    void authorityLossAndAcceptanceBothCommitOrdersUseRealScopeWaits(String scenario) throws Exception {
        String kind = scenario.split(":")[0]; boolean acceptFirst = scenario.endsWith("accept");
        int orgId = workspaceMapper.getOrgId(workspace.getId());
        if (kind.equals("ORGANIZATION")) {
            as(target); offers.decline(offerId, target.getId());
            orgMembers.addMember(orgId, currentUser.getId(), "owner"); orgMembers.addMember(orgId, coOwner.getId(), "owner");
            as(currentUser); offerId = Long.parseLong(organizations.setMember(orgId, currentUser.getId(), target.getId(), "owner").offer().id());
        }
        CountDownLatch held = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        OwnershipOfferMapper real = sql.getMapper(OwnershipOfferMapper.class);
        if (acceptFirst) {
            doAnswer(call -> { held.countDown(); await(release); return real.accept(call.getArgument(0), call.getArgument(1)); })
                .when(ledger).accept(anyLong(), anyInt());
        } else {
            doAnswer(call -> { held.countDown(); await(release); return real.close(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)); })
                .when(ledger).close(anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        }
        Runnable accept = () -> { as(target); offers.accept(offerId, target.getId(), false); };
        Runnable demote = () -> { as(coOwner); if (kind.equals("WORKSPACE")) {
            workspaces.changeMemberRole(workspace.getId(), coOwner.getId(), currentUser.getId(), "member");
        } else { organizations.setMember(orgId, coOwner.getId(), currentUser.getId(), "admin"); } };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(acceptFirst ? accept : demote); assertTrue(held.await(30, TimeUnit.SECONDS));
            Probe probe = new Probe(new AtomicLong(), new CountDownLatch(1));
            var second = executor.submit(() -> { PROBE.set(probe); try {
                (acceptFirst ? demote : accept).run(); return "committed";
            } catch (ooo.klae.connex.backend.exceptions.ConflictException refused) { return "refused"; }
            finally { PROBE.remove(); } });
            assertTrue(probe.known().await(30, TimeUnit.SECONDS));
            MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, probe.connection().get(),
                kind.equals("WORKSPACE") ? "app_user" : "organization",
                Integer.toString(kind.equals("WORKSPACE") ? currentUser.getId() : orgId));
            release.countDown(); first.get(30, TimeUnit.SECONDS);
            assertEquals(acceptFirst ? "committed" : "refused", second.get(30, TimeUnit.SECONDS));
            assertEquals(acceptFirst ? "ACCEPTED" : "INVALIDATED", ledger.findById(offerId).getState());
            assertEquals(acceptFirst ? "owner" : (kind.equals("WORKSPACE") ? "member" : null),
                kind.equals("WORKSPACE") ? workspaceMapper.getRole(workspace.getId(), target.getId()) : orgMembers.getRole(orgId, target.getId()));
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS)); }
    }

    @ParameterizedTest @ValueSource(strings = {"accept", "decline", "revoke", "expiry", "offeror-erasure", "offeree-erasure"})
    void deliveryHeldAfterRechecksSerializesAClosingWorker(String action) throws Exception {
        CountDownLatch checked = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        OwnershipOfferMapper real = sql.getMapper(OwnershipOfferMapper.class);
        doAnswer(call -> {
            if ((long) call.getArgument(0) == offerId) { checked.countDown(); await(release); }
            return real.markDelivered(call.getArgument(0), call.getArgument(1), call.getArgument(2));
        }).when(ledger).markDelivered(anyLong(), anyInt(), org.mockito.ArgumentMatchers.anyLong());
        var executor = Executors.newFixedThreadPool(2);
        try {
            if (action.equals("expiry")) {
                jdbc.update("UPDATE ownership_offer SET expires_at = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 5 SECOND) WHERE id = ?", offerId);
            }
            var persisted = executor.submit(() -> delivery.deliver(ledger.findById(offerId)));
            assertTrue(checked.await(30, TimeUnit.SECONDS));
            if (action.equals("expiry")) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!ledger.isExpired(offerId) && System.nanoTime() < deadline) {
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                }
                assertTrue(ledger.isExpired(offerId));
            }
            Probe probe = new Probe(new AtomicLong(), new CountDownLatch(1));
            var closed = executor.submit(() -> { PROBE.set(probe); try { close(action); } finally { PROBE.remove(); } });
            assertTrue(probe.known().await(30, TimeUnit.SECONDS));
            int blockedAccount = action.equals("offeree-erasure") ? target.getId() : currentUser.getId();
            MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, probe.connection().get(), "app_user", Integer.toString(blockedAccount));
            release.countDown(); persisted.get(30, TimeUnit.SECONDS); closed.get(30, TimeUnit.SECONDS);
            assertNeverOpenNotice();
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS)); }
    }

    @ParameterizedTest @ValueSource(strings = {"accept", "decline", "revoke", "expiry", "offeror-erasure", "offeree-erasure"})
    void closureHeldUnderOfferXMakesADelayedWorkerWriteNothing(String action) throws Exception {
        expireBeforeWorkerIfNeeded(action);
        OwnershipOffer discovered = ledger.findById(offerId);
        CountDownLatch locked = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        OwnershipOfferMapper real = sql.getMapper(OwnershipOfferMapper.class);
        doAnswer(call -> {
            OwnershipOffer row = real.lockById(call.getArgument(0));
            if ((long) call.getArgument(0) == offerId) { locked.countDown(); await(release); }
            return row;
        }).when(ledger).lockById(anyLong());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var closed = executor.submit(() -> close(action)); assertTrue(locked.await(30, TimeUnit.SECONDS));
            Probe probe = new Probe(new AtomicLong(), new CountDownLatch(1));
            var persisted = executor.submit(() -> { PROBE.set(probe); try { delivery.deliver(discovered); } finally { PROBE.remove(); } });
            assertTrue(probe.known().await(30, TimeUnit.SECONDS));
            int blockedAccount = action.equals("offeree-erasure") ? target.getId() : currentUser.getId();
            MySqlLockWaitProbe.awaitSharedRecordLock(jdbc, probe.connection().get(), "app_user", Integer.toString(blockedAccount));
            release.countDown(); closed.get(30, TimeUnit.SECONDS); persisted.get(30, TimeUnit.SECONDS);
            assertNeverOpenNotice();
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS)); }
    }
}
