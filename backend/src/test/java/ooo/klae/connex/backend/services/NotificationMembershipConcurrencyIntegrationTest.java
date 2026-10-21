package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.NotificationMembershipAuthority;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.NotificationControlMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;
import ooo.klae.connex.backend.support.TestEntityFixtures;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * CI-only MySQL characterization of legacy notification SQL and the prerequisite exact fences.
 * Clearing MyBatis's local cache isolates database read-view behavior. Raw member deletion
 * deliberately retains notification rows, modeling failed deferred cleanup after revocation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationMembershipConcurrencyIntegrationTest {
    private static final String AS_OF = "2026-10-20 12:00:00";

    @Autowired private NotificationMapper notifications;
    @Autowired private NotificationControlMapper controlMapper;
    @Autowired private NotificationControlAccess controlAccess;
    @Autowired private WorkspaceMapper workspaces;
    @Autowired private UserMapper users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlSessionTemplate sessions;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private TenantContext tenantContext;

    private Workspace workspace;
    private Workspace sibling;
    private User recipient;

    @BeforeEach
    void setUp() {
        tenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
        workspace = newWorkspace();
        sibling = newWorkspace();
        recipient = TestEntityFixtures.newUser(users, workspaces, workspace);
        workspaces.addMember(sibling.getId(), recipient.getId(), "member");
    }

    @AfterEach
    void cleanUp() {
        tenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
        if (recipient != null) {
            jdbc.update("DELETE FROM notification WHERE recipient_id = ?", recipient.getId());
            jdbc.update("DELETE FROM notification_recipient_state WHERE recipient_id = ?", recipient.getId());
            jdbc.update("DELETE FROM workspace_member WHERE user_id = ?", recipient.getId());
            users.delete(recipient.getId());
        }
        if (sibling != null) {
            jdbc.update("DELETE FROM workspace WHERE id = ?", sibling.getId());
        }
        if (workspace != null) {
            jdbc.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {TransactionDefinition.ISOLATION_READ_COMMITTED,
        TransactionDefinition.ISOLATION_REPEATABLE_READ})
    void legacyDepartureAndRejoinFollowTheExistingReadView(int isolation) throws Exception {
        notifications.upsert(notification("task.due"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            readTransaction(isolation).executeWithoutResult(status -> {
                var captured = controlMapper.findRecipientMemberships(recipient.getId()).stream()
                    .filter(member -> member.workspaceId() == workspace.getId()).findFirst().orElseThrow();
                long originalGeneration = captured.membershipId();
                assertEquals(1, countInbox());
                committed(worker, () -> workspaces.removeMember(workspace.getId(), recipient.getId()));
                sessions.clearCache();
                assertEquals(isolation == TransactionDefinition.ISOLATION_READ_COMMITTED ? 0 : 1,
                    countInbox());
                long newGeneration = committed(worker, () -> {
                    workspaces.addMember(workspace.getId(), recipient.getId(), "member");
                    return workspaces.getMembershipGenerationId(workspace.getId(), recipient.getId());
                });
                assertNotEquals(originalGeneration, newGeneration);
                sessions.clearCache();
                assertEquals(1, countInbox());
                long visibleGeneration = controlMapper.findRecipientMemberships(recipient.getId()).stream()
                    .filter(member -> member.workspaceId() == workspace.getId()).findFirst().orElseThrow()
                    .membershipId();
                assertEquals(isolation == TransactionDefinition.ISOLATION_READ_COMMITTED
                    ? newGeneration : originalGeneration, visibleGeneration);
                assertEquals(originalGeneration, captured.membershipId());
            });
        } finally {
            stop(worker);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {TransactionDefinition.ISOLATION_READ_COMMITTED,
        TransactionDefinition.ISOLATION_REPEATABLE_READ})
    void legacyPendingActivationWithinAnOperationFollowsItsIsolation(int isolation) throws Exception {
        jdbc.update("UPDATE workspace_member SET status = 'pending' WHERE workspace_id = ? AND user_id = ?",
            workspace.getId(), recipient.getId());
        notifications.upsert(notification("task.due"));
        notifications.upsert(notification("workspace.join"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            readTransaction(isolation).executeWithoutResult(status -> {
                var captured = controlMapper.findRecipientMemberships(recipient.getId()).stream()
                    .filter(member -> member.workspaceId() == workspace.getId()).findFirst().orElseThrow();
                assertEquals("pending", captured.status());
                assertEquals(1, countInbox());
                committed(worker, () -> jdbc.update(
                    "UPDATE workspace_member SET status = 'active' WHERE workspace_id = ? AND user_id = ?",
                    workspace.getId(), recipient.getId()));
                sessions.clearCache();
                assertEquals(isolation == TransactionDefinition.ISOLATION_READ_COMMITTED ? 2 : 1,
                    countInbox());
                assertFalse(captured.admits("task.due"));
                assertTrue(captured.admits("workspace.join"));
            });
        } finally {
            stop(worker);
        }
    }

    @Test
    void legacyLockedMutationRefusesMembershipRevokedAfterDiscovery() throws Exception {
        Notification notification = notification("task.due");
        notifications.upsert(notification);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        TransactionTemplate mutation = new TransactionTemplate(transactions);
        mutation.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        try {
            mutation.executeWithoutResult(status -> {
                assertNotNull(notifications.findById(recipient.getId(), notification.getId()));
                committed(worker, () -> workspaces.removeMember(workspace.getId(), recipient.getId()));
                assertFalse(notifications.lockRecipientMemberships(recipient.getId()).contains(workspace.getId()));
                assertNull(notifications.findByIdForUpdate(recipient.getId(), notification.getId()));
                assertEquals(0, notifications.markRead(recipient.getId(), notification.getId()));
                assertNull(jdbc.queryForObject("SELECT read_at FROM notification WHERE id = ?",
                    String.class, notification.getId()));
            });
        } finally {
            stop(worker);
        }
    }

    @Test
    void currentFenceSeesRejoinedGenerationAfterARepeatableReadLockWait() throws Exception {
        jdbc.update("UPDATE workspace_member SET status = 'pending' WHERE workspace_id = ? AND user_id = ?",
            workspace.getId(), recipient.getId());
        long originalGeneration = workspaces.getMembershipGenerationId(workspace.getId(), recipient.getId());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        CountDownLatch waiterReady = new CountDownLatch(1);
        AtomicLong waitingConnection = new AtomicLong();
        try {
            Future<Long> holder = workers.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                assertNotNull(workspaces.lockAuthorizationMembership(workspace.getId(), recipient.getId()));
                workspaces.removeMember(workspace.getId(), recipient.getId());
                workspaces.addMember(workspace.getId(), recipient.getId(), "member");
                long generation = workspaces.getMembershipGenerationId(workspace.getId(), recipient.getId());
                holderReady.countDown();
                await(releaseHolder);
                return generation;
            }));
            await(holderReady);
            Future<NotificationMembershipAuthority> waiter = workers.submit(() -> {
                TransactionTemplate mutation = new TransactionTemplate(transactions);
                mutation.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                return mutation.execute(status -> {
                    assertEquals(recipient.getId(), controlAccess.lockActorForShare(recipient.getId()));
                    var captured = controlMapper.findRecipientMemberships(recipient.getId()).stream()
                        .filter(member -> member.workspaceId() == workspace.getId()).findFirst().orElseThrow();
                    assertEquals("pending", captured.status());
                    assertEquals(originalGeneration, captured.membershipId());
                    waitingConnection.set(connectionId());
                    waiterReady.countDown();
                    return controlAccess.lockMembershipForShare(workspace.getId(), recipient.getId());
                });
            });
            await(waiterReady);
            MySqlLockWaitProbe.awaitSharedRecordLock(jdbc, waitingConnection.get(),
                "workspace_member", workspace.getId() + ", " + recipient.getId());
            releaseHolder.countDown();
            long rejoinedGeneration = holder.get(30, TimeUnit.SECONDS);
            var authority = waiter.get(30, TimeUnit.SECONDS);
            assertNotNull(authority);
            assertNotEquals(originalGeneration, rejoinedGeneration);
            assertEquals("active", authority.status());
            assertEquals(rejoinedGeneration, authority.membershipId());
        } finally {
            releaseHolder.countDown();
            stop(workers);
        }
    }

    @Test
    void exactSharedMembershipFenceBlocksDepartureWithoutLockingSiblingMembership() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch attemptingDelete = new CountDownLatch(1);
        AtomicLong deletingConnection = new AtomicLong();
        AtomicReference<Future<Integer>> departure = new AtomicReference<>();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                long callerConnection = connectionId();
                var authority = controlAccess.lockMembershipForShare(workspace.getId(), recipient.getId());
                assertNotNull(authority);
                assertEquals("active", authority.status());
                assertEquals(callerConnection, connectionId());
                committed(worker, () -> workspaces.removeMember(sibling.getId(), recipient.getId()));
                departure.set(worker.submit(() -> new TransactionTemplate(transactions).execute(deleting -> {
                    deletingConnection.set(connectionId());
                    attemptingDelete.countDown();
                    return workspaces.removeMember(workspace.getId(), recipient.getId());
                })));
                await(attemptingDelete);
                assertNotEquals(callerConnection, deletingConnection.get());
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, deletingConnection.get(),
                    "workspace_member", workspace.getId() + ", " + recipient.getId());
                assertFalse(departure.get().isDone());
            });
            assertEquals(1, departure.get().get(30, TimeUnit.SECONDS));
            new TransactionTemplate(transactions).executeWithoutResult(status ->
                assertNull(controlAccess.lockMembershipForUpdate(workspace.getId(), recipient.getId())));
        } finally {
            stop(worker);
        }
    }

    @Test
    void actorFenceRetainsLiveAccountOnTheTenantTransactionConnection() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch attemptingDelete = new CountDownLatch(1);
        AtomicLong deletingConnection = new AtomicLong();
        AtomicReference<Future<Integer>> deletion = new AtomicReference<>();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                long callerConnection = connectionId();
                assertEquals(recipient.getId(), controlAccess.lockActorForShare(recipient.getId()));
                assertEquals(callerConnection, connectionId());
                deletion.set(worker.submit(() -> new TransactionTemplate(transactions).execute(deleting -> {
                    deletingConnection.set(connectionId());
                    attemptingDelete.countDown();
                    return users.delete(recipient.getId());
                })));
                await(attemptingDelete);
                MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, deletingConnection.get(),
                    "app_user", Integer.toString(recipient.getId()));
                assertFalse(deletion.get().isDone());
            });
            assertEquals(1, deletion.get().get(30, TimeUnit.SECONDS));
            new TransactionTemplate(transactions).executeWithoutResult(status ->
                assertNull(controlAccess.lockActorForShare(recipient.getId())));
        } finally {
            stop(worker);
        }
    }

    private Workspace newWorkspace() {
        Workspace created = new Workspace();
        created.setName("Notification authority " + UUID.randomUUID());
        created.setSlug("notification-authority-" + UUID.randomUUID());
        workspaces.insert(created);
        return created;
    }

    private Notification notification(String type) {
        Notification notification = new Notification();
        notification.setWorkspaceId(workspace.getId());
        notification.setRecipientId(recipient.getId());
        notification.setType(type);
        notification.setCategory("workspace.join".equals(type) ? "workspace" : "task");
        notification.setSeverity("info");
        notification.setTemplateVersion(1);
        notification.setTitle("Notification authority characterization");
        notification.setBody("Content-free fixture");
        notification.setDedupeKey(type + ":" + UUID.randomUUID());
        notification.setTriggeredAt(AS_OF);
        return notification;
    }

    private long countInbox() {
        return notifications.countPage(recipient.getId(), "all", null, null, null, workspace.getId(),
            null, null, AS_OF);
    }

    private TransactionTemplate readTransaction(int isolation) {
        TransactionTemplate read = new TransactionTemplate(transactions);
        read.setIsolationLevel(isolation);
        read.setReadOnly(true);
        return read;
    }

    private long connectionId() {
        return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
    }

    private <T> T committed(ExecutorService worker, java.util.concurrent.Callable<T> change) {
        try {
            return worker.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                try {
                    return change.call();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            })).get(30, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent membership change did not commit", exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void stop(ExecutorService worker) throws InterruptedException {
        worker.shutdownNow();
        assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS));
    }
}
