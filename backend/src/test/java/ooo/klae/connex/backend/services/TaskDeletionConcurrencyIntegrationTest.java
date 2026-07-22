package ooo.klae.connex.backend.services;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
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
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private TaskMapper taskMapperSpy;
    @MockitoBean private NotificationChangePublisher notificationChanges;

    @Test
    void concurrentDeletesHaveOneWinnerAndOneSetOfSideEffects() throws Exception {
        Task task = newTask(currentUser, null, null);
        int workspaceId = workspace.getId();
        int taskId = task.getId();
        Integer orgId = workspaceMapper.getOrgId(workspaceId);
        int resolvedOrgId = orgId == null ? workspaceId : orgId;
        CountDownLatch bothStarted = new CountDownLatch(2);
        doAnswer(invocation -> {
            bothStarted.countDown();
            assertTrue(bothStarted.await(10, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(taskMapperSpy).getTaskByIdForUpdate(eq(workspaceId), eq(taskId));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(
                () -> deleteTask(taskId, workspaceId, resolvedOrgId));
            Future<Boolean> second = executor.submit(
                () -> deleteTask(taskId, workspaceId, resolvedOrgId));

            int successes = (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertNull(taskMapper.getTaskById(workspaceId, taskId));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND action = 'task.delete' AND entity_id = ?",
            Integer.class,
            workspaceId,
            taskId
        ));
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
