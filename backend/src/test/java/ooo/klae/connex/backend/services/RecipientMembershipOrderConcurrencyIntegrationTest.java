package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OrgMemberMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import ooo.klae.connex.backend.support.TestEntityFixtures;

/** User-major recipient traversal resolves owner crossover contention through the NOWAIT guard. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecipientMembershipOrderConcurrencyIntegrationTest extends AbstractServiceTest {
    @MockitoSpyBean private WorkspaceMapper roots;
    @MockitoSpyBean private NotificationMapper inboxMemberships;
    @Autowired private OrganizationMapper organizations;
    @Autowired private OrgMemberMapper organizationMembers;
    @Autowired private NotificationStateVersionService notificationState;
    @Autowired private UserAccountCatalogOffboardingService catalogOffboarding;
    @Autowired private UserDeletionTransaction deletion;
    @Autowired private PersonService persons;
    @Autowired private AuditIntegrityService integrity;
    @Autowired private GlobalExceptionHandler exceptionHandler;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SqlSessionTemplate sql;
    @Autowired private JdbcTemplate jdbc;
    private final List<Integer> accountIds = new ArrayList<>();
    private final List<Integer> workspaceIds = new ArrayList<>();
    private int organizationId;
    private int firstWorkspaceId;
    private int secondWorkspaceId;
    private User erasingAccount;
    private User secondRecipient;
    private User thirdRecipient;
    private Person person;
    private Notification secondNotice;
    private Notification thirdNotice;
    private Notification departingNotice;
    private final String erasureReservation = UUID.randomUUID().toString();

    @Override
    protected User newUser() {
        User user = super.newUser();
        accountIds.add(user.getId());
        return user;
    }

    @BeforeEach
    void committedOwnerCrossoverFixtures() {
        roots.removeMember(workspace.getId(), currentUser.getId());
        Organization organization = new Organization();
        organization.setName("Recipient membership " + unique());
        organization.setSlug("recipient-membership-" + unique());
        organizations.insert(organization);
        organizationId = organization.getId();
        workspace = newWorkspace();
        firstWorkspaceId = workspace.getId();
        secondWorkspaceId = newWorkspace().getId();
        roots.addMember(firstWorkspaceId, currentUser.getId(), "owner");
        roots.addMember(secondWorkspaceId, currentUser.getId(), "owner");
        erasingAccount = newUser();
        secondRecipient = newUser();
        thirdRecipient = newUser();
        roots.addMember(secondWorkspaceId, secondRecipient.getId(), "owner");
        roots.updateMemberRole(firstWorkspaceId, thirdRecipient.getId(), "owner");
        for (User owner : List.of(currentUser, secondRecipient, thirdRecipient)) {
            organizationMembers.addMember(organizationId, owner.getId(), "owner");
        }
        authenticateAs(currentUser, firstWorkspaceId);
        approveForStepUp(currentUser);
        person = newPerson(null);
        persons.updateOwner(person.getId(), erasingAccount.getId());
        secondNotice = notice(secondWorkspaceId, secondRecipient.getId());
        thirdNotice = notice(firstWorkspaceId, thirdRecipient.getId());
        departingNotice = notice(firstWorkspaceId, erasingAccount.getId());
        assertTrue(inboxMemberships.getStateVersion(secondRecipient.getId()) > 0);
        assertTrue(inboxMemberships.getStateVersion(thirdRecipient.getId()) > 0);
        deletion.reserve(erasingAccount.getId(), erasureReservation);
        assertTrue(firstWorkspaceId < secondWorkspaceId);
        assertTrue(secondRecipient.getId() < thirdRecipient.getId());
    }

    @AfterEach
    void removeCommittedFixtures() {
        reset(roots, inboxMemberships);
        for (int workspaceId : workspaceIds) {
            jdbc.update("DELETE FROM notification WHERE workspace_id = ?", workspaceId);
            jdbc.update("DELETE FROM person WHERE workspace_id = ?", workspaceId);
            TestEntityFixtures.deleteChatPrivacy(jdbc, workspaceId);
            jdbc.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        }
        jdbc.update("DELETE FROM organization WHERE id = ?", organizationId);
        accountIds.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
        workspaceIds.clear();
        accountIds.clear();
    }

    @Test
    void recipientErasureCompletesWhenOwnerGuardRefusesTheCrossedMembership() throws Exception {
        CountDownLatch secondRecipientHeld = new CountDownLatch(1);
        CountDownLatch releaseErasure = new CountDownLatch(1);
        CountDownLatch firstOwnersHeld = new CountDownLatch(1);
        CountDownLatch releaseGuard = new CountDownLatch(1);
        CountDownLatch requestingThirdRecipient = new CountDownLatch(1);
        AtomicLong erasureConnection = new AtomicLong();
        AtomicLong guardConnection = new AtomicLong();
        AtomicInteger secondOwnerScans = new AtomicInteger();
        AtomicReference<SQLException> guardSqlFailure = new AtomicReference<>();
        WorkspaceMapper real = sql.getMapper(WorkspaceMapper.class);
        NotificationMapper realInbox = sql.getMapper(NotificationMapper.class);
        doAnswer(call -> {
            List<Integer> retainedWorkspaces = realInbox.lockRecipientMemberships(secondRecipient.getId());
            assertTrue(retainedWorkspaces.contains(secondWorkspaceId));
            erasureConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            secondRecipientHeld.countDown();
            assertTrue(releaseErasure.await(30, TimeUnit.SECONDS));
            return retainedWorkspaces;
        }).when(inboxMemberships).lockRecipientMemberships(secondRecipient.getId());
        doAnswer(call -> {
            requestingThirdRecipient.countDown();
            return realInbox.lockRecipientMemberships(thirdRecipient.getId());
        }).when(inboxMemberships).lockRecipientMemberships(thirdRecipient.getId());
        doAnswer(call -> {
            List<WorkspaceMember> owners = real.lockActiveOwnerMembers(firstWorkspaceId);
            assertTrue(owners.stream().anyMatch(owner -> owner.getUserId() == thirdRecipient.getId()));
            guardConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            firstOwnersHeld.countDown();
            assertTrue(releaseGuard.await(30, TimeUnit.SECONDS));
            return owners;
        }).when(roots).lockActiveOwnerMembers(firstWorkspaceId);
        doAnswer(call -> {
            secondOwnerScans.incrementAndGet();
            try {
                return real.lockActiveOwnerMembers(secondWorkspaceId);
            } catch (RuntimeException failure) {
                guardSqlFailure.set(sqlCause(failure));
                throw failure;
            }
        }).when(roots).lockActiveOwnerMembers(secondWorkspaceId);

        String originalName = currentUser.getDisplayName();
        List<AuditLog> initialAudits = committedAudits();
        assertEquals(1, initialAudits.size());
        assertEquals("person.updateOwner", initialAudits.getFirst().getAction());
        assertTrue(initialAudits.stream().allMatch(integrity::hasValidIntegrity));
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<?> erasure = workers.submit(() -> as(erasingAccount,
                () -> catalogOffboarding.eraseReferences(erasingAccount.getId())));
            try {
                assertTrue(secondRecipientHeld.await(30, TimeUnit.SECONDS));
                Future<ConflictException> guard = workers.submit(() -> {
                    authenticateAs(currentUser, firstWorkspaceId);
                    try {
                        return assertThrows(ConflictException.class, () -> {
                            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                            transaction.executeWithoutResult(status -> {
                                userMapper.lockById(currentUser.getId());
                                jdbc.update("UPDATE app_user SET display_name = ? WHERE id = ?",
                                    "Uncommitted owner guard", currentUser.getId());
                                deletion.reserve(currentUser.getId(), UUID.randomUUID().toString());
                            });
                        });
                    } finally {
                        clearAuthentication();
                    }
                });
                assertTrue(firstOwnersHeld.await(30, TimeUnit.SECONDS));
                assertNotEquals(guardConnection.get(), erasureConnection.get());
                releaseErasure.countDown();
                assertTrue(requestingThirdRecipient.await(30, TimeUnit.SECONDS));
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, erasureConnection.get(),
                    "workspace_member", firstWorkspaceId + ", " + thirdRecipient.getId());
                assertEquals(erasingAccount.getId(), ownerOfPerson());
                assertEquals(erasingAccount.getId(), noticeActor(secondNotice));
                assertEquals(erasingAccount.getId(), noticeActor(thirdNotice));
                assertEquals(originalName, userMapper.getUserById(currentUser.getId()).getDisplayName());
                releaseGuard.countDown();
                ConflictException conflict = completed(guard);
                assertEquals(409, exceptionHandler.conflict(conflict).getStatusCode().value());
                completed(erasure);
            } finally {
                releaseErasure.countDown();
                releaseGuard.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS));
            }
        }
        assertEquals(1, secondOwnerScans.get());
        SQLException sqlFailure = guardSqlFailure.get();
        assertNotNull(sqlFailure);
        assertEquals(3572, sqlFailure.getErrorCode());
        assertEquals("HY000", sqlFailure.getSQLState());
        assertEquals(originalName, userMapper.getUserById(currentUser.getId()).getDisplayName());
        assertFalse(userMapper.isAccountDeletionReserved(currentUser.getId()));
        assertTrue(userMapper.isAccountDeletionReserved(erasingAccount.getId()));
        assertEquals("owner", roots.getRole(firstWorkspaceId, currentUser.getId()));
        assertEquals("owner", roots.getRole(secondWorkspaceId, currentUser.getId()));
        assertEquals("owner", roots.getRole(firstWorkspaceId, thirdRecipient.getId()));
        assertEquals("owner", roots.getRole(secondWorkspaceId, secondRecipient.getId()));
        assertNull(ownerOfPerson());
        assertNull(noticeActor(secondNotice));
        assertNull(noticeActor(thirdNotice));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE id = ?",
            Integer.class, departingNotice.getId()));
        List<AuditLog> committed = committedAudits();
        assertEquals(initialAudits.stream().map(AuditLog::getRowHash).toList(),
            committed.stream().map(AuditLog::getRowHash).toList());
        assertTrue(committed.stream().allMatch(integrity::hasValidIntegrity));
    }

    private Workspace newWorkspace() {
        Workspace created = new Workspace();
        created.setOrgId(organizationId);
        created.setName("Recipient membership workspace");
        created.setSlug("recipient-membership-" + unique());
        roots.insert(created);
        workspaceIds.add(created.getId());
        TestEntityFixtures.seedExistingWorkspacePrivacy(jdbc, created.getId());
        return created;
    }

    private Notification notice(int workspaceId, int recipientId) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return Objects.requireNonNull(transaction.execute(status -> {
            Notification notice = newNotification(workspaceId, recipientId);
            jdbc.update("UPDATE notification SET actor_id = ? WHERE id = ?",
                erasingAccount.getId(), notice.getId());
            notificationState.markChanged(recipientId);
            return notice;
        }), "committed notification fixture");
    }

    private Integer ownerOfPerson() {
        return jdbc.queryForObject("SELECT owner_id FROM person WHERE id = ?", Integer.class, person.getId());
    }

    private Integer noticeActor(Notification notice) {
        return jdbc.queryForObject("SELECT actor_id FROM notification WHERE id = ?",
            Integer.class, notice.getId());
    }

    private List<AuditLog> committedAudits() {
        return jdbc.query("""
            SELECT audit_log.*,
                   untrusted_client_asserted_correlation_id AS untrusted_client_asserted_correlation_hmac
            FROM audit_log WHERE integrity_workspace_id = ? ORDER BY chain_index
            """, BeanPropertyRowMapper.newInstance(AuditLog.class), firstWorkspaceId);
    }

    private void as(User user, Runnable action) {
        authenticateAs(user, firstWorkspaceId);
        try {
            action.run();
        } finally {
            clearAuthentication();
        }
    }

    private static SQLException sqlCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlFailure) {
                assertNotEquals(1213, sqlFailure.getErrorCode());
                assertNotEquals(1205, sqlFailure.getErrorCode());
                return sqlFailure;
            }
        }
        throw new AssertionError("Expected an intentional MySQL NOWAIT refusal", failure);
    }

    private static <T> T completed(Future<T> future) throws Exception {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sqlFailure) {
                    assertNotEquals(1213, sqlFailure.getErrorCode());
                    assertNotEquals(1205, sqlFailure.getErrorCode());
                }
            }
            throw failure;
        }
    }
}
