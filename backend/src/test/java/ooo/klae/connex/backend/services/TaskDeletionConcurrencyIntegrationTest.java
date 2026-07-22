package ooo.klae.connex.backend.services;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskDeletionConcurrencyIntegrationTest extends AbstractServiceTest {

    @Autowired private TaskService taskService;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private TaskMapper taskMapperSpy;
    @MockitoBean private AuditService auditService;
    @MockitoBean private NotificationChangePublisher notificationChanges;

    @AfterEach
    void cleanUpCommittedUser() {
        if (currentUser == null) return;
        jdbcTemplate.update("DELETE FROM workspace_member WHERE user_id = ?", currentUser.getId());
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());
    }

    @Test
    void concurrentDeletesHaveOneWinnerAndOneSetOfSideEffects() throws Exception {
        Task task = newTask(currentUser, null, null);
        int workspaceId = workspace.getId();
        int taskId = task.getId();
        Integer orgId = workspaceMapper.getOrgId(workspaceId);
        int resolvedOrgId = orgId == null ? workspaceId : orgId;
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondReadStarted = new CountDownLatch(1);
        CountDownLatch secondReadCompleted = new CountDownLatch(1);
        AtomicInteger lockReads = new AtomicInteger();
        TaskMapper realTaskMapper = sqlSessionTemplate.getMapper(TaskMapper.class);
        doAnswer(invocation -> {
            int lockRead = lockReads.incrementAndGet();
            if (lockRead == 2) secondReadStarted.countDown();
            Task locked = realTaskMapper.getTaskByIdForUpdate(workspaceId, taskId);
            if (lockRead == 1) {
                firstLocked.countDown();
                assertTrue(releaseFirst.await(10, TimeUnit.SECONDS));
            } else {
                secondReadCompleted.countDown();
            }
            return locked;
        }).when(taskMapperSpy).getTaskByIdForUpdate(eq(workspaceId), eq(taskId));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(
                () -> deleteTask(taskId, workspaceId, resolvedOrgId));
            assertTrue(firstLocked.await(10, TimeUnit.SECONDS));
            Future<Boolean> second = executor.submit(
                () -> deleteTask(taskId, workspaceId, resolvedOrgId));
            assertTrue(secondReadStarted.await(10, TimeUnit.SECONDS));
            assertFalse(secondReadCompleted.await(1, TimeUnit.SECONDS));
            releaseFirst.countDown();

            int successes = (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

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
}
