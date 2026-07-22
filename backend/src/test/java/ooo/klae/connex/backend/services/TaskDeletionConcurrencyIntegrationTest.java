package ooo.klae.connex.backend.services;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.tenant.TenantContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskDeletionConcurrencyIntegrationTest {

    @Autowired private TaskService taskService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private TaskMapper taskMapperSpy;
    @MockitoBean private AuditService auditService;
    @MockitoBean private NotificationChangePublisher notificationChanges;
    @MockitoBean private ReferenceService referenceService;
    @MockitoBean private RuleTriggerPublisher ruleTriggers;

    private Organization organization;
    private Workspace workspace;
    private User currentUser;
    private Task task;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Task deletion " + unique);
        organization.setSlug("task-deletion-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Task deletion " + unique);
        workspace.setSlug("task-deletion-" + unique);
        workspaceMapper.insert(workspace);

        currentUser = new User();
        currentUser.setUsername("task-delete-" + unique);
        currentUser.setDisplayName("Task deletion " + unique);
        currentUser.setEmail("task-delete-" + unique + "@example.com");
        currentUser.setPasswordHash("hash-" + unique);
        currentUser.setTimezone("UTC");
        userMapper.insert(currentUser);
        workspaceMapper.addMember(workspace.getId(), currentUser.getId(), "owner");

        task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("Delete once " + unique);
        task.setCompleted(false);
        task.setStatus("todo");
        task.setAssignedTo(currentUser);
        taskMapper.insert(task);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        tenantContext.clear();
        if (task != null && workspace != null) {
            jdbcTemplate.update(
                "DELETE FROM entity_reference WHERE workspace_id = ? AND source_type = 'task' AND source_id = ?",
                workspace.getId(),
                task.getId()
            );
            jdbcTemplate.update(
                "DELETE FROM task WHERE workspace_id = ? AND id = ?",
                workspace.getId(),
                task.getId()
            );
        }
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM task_board_lock WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (currentUser != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void concurrentDeletesHaveOneWinnerAndOneSetOfSideEffects() throws Exception {
        int workspaceId = workspace.getId();
        int taskId = task.getId();
        int orgId = organization.getId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(
                () -> deleteTaskAfterRelease(taskId, workspaceId, orgId, ready, start));
            Future<Boolean> second = executor.submit(
                () -> deleteTaskAfterRelease(taskId, workspaceId, orgId, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            int successes = (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertDeleteSideEffectsOccurredOnce();
    }

    @Test
    void boardRootMakesConcurrentDeleteHitDatabaseLockTimeout() throws Exception {
        int workspaceId = workspace.getId();
        int taskId = task.getId();
        int orgId = organization.getId();
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondReadStarted = new CountDownLatch(1);
        AtomicInteger lockAttempts = new AtomicInteger();
        TaskMapper realTaskMapper = sqlSessionTemplate.getMapper(TaskMapper.class);
        doAnswer(invocation -> {
            int lockAttempt = lockAttempts.incrementAndGet();
            if (lockAttempt == 2) {
                secondReadStarted.countDown();
                Integer previousTimeout = jdbcTemplate.queryForObject(
                    "SELECT @@SESSION.innodb_lock_wait_timeout",
                    Integer.class
                );
                jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = 1");
                try {
                    realTaskMapper.lockTaskBoard(workspaceId);
                    return null;
                } finally {
                    if (previousTimeout != null) {
                        jdbcTemplate.execute(
                            "SET SESSION innodb_lock_wait_timeout = " + previousTimeout);
                    }
                }
            }
            realTaskMapper.lockTaskBoard(workspaceId);
            if (lockAttempt == 1) {
                firstLocked.countDown();
                assertTrue(releaseFirst.await(30, TimeUnit.SECONDS));
            }
            return null;
        }).when(taskMapperSpy).lockTaskBoard(workspaceId);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(() -> deleteTask(taskId, workspaceId, orgId));
            assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
            Future<Boolean> second = executor.submit(() -> deleteTask(taskId, workspaceId, orgId));
            assertTrue(secondReadStarted.await(10, TimeUnit.SECONDS));

            ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> second.get(5, TimeUnit.SECONDS)
            );
            assertTrue(hasCause(failure, CannotAcquireLockException.class));
            releaseFirst.countDown();
            assertTrue(first.get(20, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertDeleteSideEffectsOccurredOnce();
    }

    @Test
    void boardLockedDeleteMakesConcurrentUpdateHitDatabaseLockTimeout() throws Exception {
        int workspaceId = workspace.getId();
        int taskId = task.getId();
        int orgId = organization.getId();
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondReadStarted = new CountDownLatch(1);
        AtomicInteger lockAttempts = new AtomicInteger();
        TaskMapper realTaskMapper = sqlSessionTemplate.getMapper(TaskMapper.class);
        doAnswer(invocation -> {
            int lockAttempt = lockAttempts.incrementAndGet();
            if (lockAttempt == 2) {
                secondReadStarted.countDown();
                Integer previousTimeout = jdbcTemplate.queryForObject(
                    "SELECT @@SESSION.innodb_lock_wait_timeout",
                    Integer.class
                );
                jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = 1");
                try {
                    realTaskMapper.lockTaskBoard(workspaceId);
                    return null;
                } finally {
                    if (previousTimeout != null) {
                        jdbcTemplate.execute(
                            "SET SESSION innodb_lock_wait_timeout = " + previousTimeout);
                    }
                }
            }
            realTaskMapper.lockTaskBoard(workspaceId);
            if (lockAttempt == 1) {
                firstLocked.countDown();
                assertTrue(releaseFirst.await(30, TimeUnit.SECONDS));
            }
            return null;
        }).when(taskMapperSpy).lockTaskBoard(workspaceId);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> deletion = executor.submit(() -> deleteTask(taskId, workspaceId, orgId));
            assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
            Future<Task> update = executor.submit(() -> updateTask(taskId, workspaceId, orgId));
            assertTrue(secondReadStarted.await(10, TimeUnit.SECONDS));

            ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> update.get(5, TimeUnit.SECONDS)
            );
            assertTrue(hasCause(failure, CannotAcquireLockException.class));
            releaseFirst.countDown();
            assertTrue(deletion.get(20, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertDeleteSideEffectsOccurredOnce();
    }

    @Test
    void updateWaitingOnCommittedDeleteReturnsNotFoundWithoutSuccessSideEffects() throws Exception {
        int workspaceId = workspace.getId();
        int taskId = task.getId();
        int orgId = organization.getId();
        CountDownLatch deleteLocked = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        CountDownLatch updateReadStarted = new CountDownLatch(1);
        AtomicInteger lockAttempts = new AtomicInteger();
        TaskMapper realTaskMapper = sqlSessionTemplate.getMapper(TaskMapper.class);
        doAnswer(invocation -> {
            int lockAttempt = lockAttempts.incrementAndGet();
            if (lockAttempt == 2) updateReadStarted.countDown();
            realTaskMapper.lockTaskBoard(workspaceId);
            if (lockAttempt == 1) {
                deleteLocked.countDown();
                assertTrue(releaseDelete.await(30, TimeUnit.SECONDS));
            }
            return null;
        }).when(taskMapperSpy).lockTaskBoard(workspaceId);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> deletion = executor.submit(() -> deleteTask(taskId, workspaceId, orgId));
            assertTrue(deleteLocked.await(10, TimeUnit.SECONDS));
            Future<Task> update = executor.submit(() -> updateTask(taskId, workspaceId, orgId));
            assertTrue(updateReadStarted.await(10, TimeUnit.SECONDS));
            releaseDelete.countDown();

            assertTrue(deletion.get(20, TimeUnit.SECONDS));
            ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> update.get(20, TimeUnit.SECONDS)
            );
            assertTrue(hasCause(failure, ResourceNotFoundException.class));
        } finally {
            releaseDelete.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertDeleteSideEffectsOccurredOnce();
    }

    private boolean deleteTaskAfterRelease(
            int taskId,
            int workspaceId,
            int orgId,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return deleteTask(taskId, workspaceId, orgId);
    }

    private boolean deleteTask(int taskId, int workspaceId, int orgId) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                currentUser, null, currentUser.getAuthorities()));
        tenantContext.set(workspaceId, orgId, currentUser.getId(), "owner", null);
        try {
            taskService.delete(taskId);
            return true;
        } catch (ResourceNotFoundException exception) {
            return false;
        } finally {
            SecurityContextHolder.clearContext();
            tenantContext.clear();
        }
    }

    private Task updateTask(int taskId, int workspaceId, int orgId) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                currentUser, null, currentUser.getAuthorities()));
        tenantContext.set(workspaceId, orgId, currentUser.getId(), "owner", null);
        Task update = new Task();
        update.setDescription("Concurrent update");
        update.setCompleted(false);
        update.setStatus("todo");
        update.setDueDate("2026-08-01");
        update.setAssignedTo(currentUser);
        try {
            return taskService.update(taskId, update);
        } finally {
            SecurityContextHolder.clearContext();
            tenantContext.clear();
        }
    }

    private void assertDeleteSideEffectsOccurredOnce() {
        int workspaceId = workspace.getId();
        int taskId = task.getId();
        assertNull(taskMapper.getTaskById(workspaceId, taskId));
        verify(auditService, times(1)).record(
            eq("task.delete"),
            eq("task"),
            eq(taskId),
            eq(task.getDescription()),
            eq("Deleted task " + task.getDescription()),
            anyMap()
        );
        verify(notificationChanges, times(1)).publish(workspaceId, "task", taskId);
        verify(referenceService, times(1)).deleteReferences(
            workspaceId,
            ReferenceService.SOURCE_TASK,
            taskId
        );
        verifyNoMoreInteractions(referenceService);
        verifyNoInteractions(ruleTriggers);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }
}
