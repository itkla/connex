package ooo.klae.connex.backend.services;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.notifications.NotificationDelivery;

@ExtendWith(MockitoExtension.class)
class TaskMutationInvariantTest {

    @Mock TaskMapper taskMapper;
    @Mock DealMapper dealMapper;
    @Mock AuditService auditService;
    @Mock WorkspaceService workspaceService;
    @Mock AuthService authService;
    @Mock UserCalendarService userCalendarService;
    @Mock NotificationChangePublisher notificationChanges;
    @Mock ReferenceService referenceService;
    @Mock NotificationDelivery notificationDelivery;
    @Mock NotificationPreferenceService notificationPreferenceService;
    @Mock RuleTriggerPublisher ruleTriggers;
    @Mock ObjectMapper objectMapper;
    @InjectMocks TaskService taskService;

    @Test
    void createLocksMembershipThenBoardBeforeAllocatingPosition() {
        int workspaceId = 17;
        Task task = task(0, "Create in order");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspaceId);
        when(referenceService.hydrateTasks(eq(workspaceId), anyList()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        when(referenceService.syncReferences(
                workspaceId, ReferenceService.SOURCE_TASK, 0, task.getDescription()))
            .thenReturn(List.of());

        taskService.create(task);

        InOrder order = inOrder(workspaceService, taskMapper);
        order.verify(workspaceService).lockAndRequireMember(workspaceId, 41);
        order.verify(taskMapper).lockTaskBoard(workspaceId);
        order.verify(taskMapper).nextTaskPosition(workspaceId, "todo");
        order.verify(taskMapper).insert(task);
    }

    @Test
    void updateSkipsSuccessSideEffectsWhenWriteLoses() {
        int workspaceId = 17;
        int taskId = 29;
        Task before = task(taskId, "Before");
        Task update = task(0, "After");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspaceId);
        when(taskMapper.getTaskByIdForUpdate(workspaceId, taskId)).thenReturn(before);
        when(taskMapper.update(update)).thenReturn(0);

        assertThrows(ResourceNotFoundException.class, () -> taskService.update(taskId, update));

        verifyNoInteractions(referenceService, auditService, notificationChanges, ruleTriggers);
    }

    @Test
    void updateLocksTargetMembershipBeforeTask() {
        int workspaceId = 17;
        int taskId = 29;
        Task before = task(taskId, "Before");
        Task update = task(0, "After");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspaceId);
        when(taskMapper.getTaskByIdForUpdate(workspaceId, taskId)).thenReturn(before);
        when(taskMapper.update(update)).thenReturn(0);

        assertThrows(ResourceNotFoundException.class, () -> taskService.update(taskId, update));

        InOrder order = inOrder(workspaceService, taskMapper);
        order.verify(workspaceService).lockAndRequireMember(workspaceId, 41);
        order.verify(taskMapper).lockTaskBoard(workspaceId);
        order.verify(taskMapper).listWorkspaceTaskIds(workspaceId);
        order.verify(taskMapper).getTaskByIdForUpdate(workspaceId, taskId);
    }

    @Test
    void updateRejectsCompletionTransitionByNonAssignee() {
        int workspaceId = 17;
        int taskId = 29;
        Task before = task(taskId, "Before");
        Task update = task(0, "After");
        update.setCompleted(true);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspaceId);
        when(taskMapper.getTaskByIdForUpdate(workspaceId, taskId)).thenReturn(before);
        when(authService.getCurrentUser()).thenReturn(user(43));

        assertThrows(ForbiddenException.class, () -> taskService.update(taskId, update));

        verify(taskMapper, never()).update(update);
        verifyNoInteractions(referenceService, auditService, notificationChanges, ruleTriggers);
    }

    @Test
    void moveSkipsSuccessSideEffectsWhenWriteLoses() {
        int workspaceId = 17;
        int taskId = 29;
        Task before = task(taskId, "Move once");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspaceId);
        when(taskMapper.listWorkspaceTaskIds(workspaceId)).thenReturn(List.of(taskId));
        when(taskMapper.getTaskByIdForUpdate(workspaceId, taskId)).thenReturn(before);
        when(taskMapper.moveTask(workspaceId, taskId, "todo", false, 0)).thenReturn(0);

        assertThrows(ResourceNotFoundException.class, () -> taskService.move(taskId, "todo", 0));

        verifyNoInteractions(referenceService, auditService, notificationChanges, ruleTriggers);
    }

    @Test
    void deleteLocksBoardBeforeExactTaskRows() {
        int workspaceId = 17;
        int taskId = 29;
        Task before = task(taskId, "Delete in order");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspaceId);
        when(taskMapper.listWorkspaceTaskIds(workspaceId)).thenReturn(List.of(taskId));
        when(taskMapper.getTaskByIdForUpdate(workspaceId, taskId)).thenReturn(before);
        when(taskMapper.delete(workspaceId, taskId)).thenReturn(1);

        taskService.delete(taskId);

        InOrder order = inOrder(taskMapper);
        order.verify(taskMapper).lockTaskBoard(workspaceId);
        order.verify(taskMapper).listWorkspaceTaskIds(workspaceId);
        order.verify(taskMapper).getTaskByIdForUpdate(workspaceId, taskId);
        order.verify(taskMapper).delete(workspaceId, taskId);
    }

    @Test
    void moveLocksDiscoveredTasksByAscendingExactId() {
        int workspaceId = 17;
        int taskId = 29;
        Task first = task(7, "First");
        Task second = task(19, "Second");
        Task root = task(taskId, "Root");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspaceId);
        when(taskMapper.listWorkspaceTaskIds(workspaceId)).thenReturn(List.of(29, 7, 19));
        when(taskMapper.getTaskByIdForUpdate(workspaceId, 7)).thenReturn(first);
        when(taskMapper.getTaskByIdForUpdate(workspaceId, 19)).thenReturn(second);
        when(taskMapper.getTaskByIdForUpdate(workspaceId, 29)).thenReturn(root);
        when(taskMapper.moveTask(workspaceId, taskId, "todo", false, 0)).thenReturn(0);

        assertThrows(ResourceNotFoundException.class, () -> taskService.move(taskId, "todo", 0));

        InOrder order = inOrder(taskMapper);
        order.verify(taskMapper).lockTaskBoard(workspaceId);
        order.verify(taskMapper).listWorkspaceTaskIds(workspaceId);
        order.verify(taskMapper).getTaskByIdForUpdate(workspaceId, 7);
        order.verify(taskMapper).getTaskByIdForUpdate(workspaceId, 19);
        order.verify(taskMapper).getTaskByIdForUpdate(workspaceId, 29);
    }

    @Test
    void rescheduleSkipsSuccessSideEffectsWhenWriteLoses() {
        int workspaceId = 17;
        int taskId = 29;
        Task before = task(taskId, "Reschedule once");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspaceId);
        when(taskMapper.getTaskByIdForUpdate(workspaceId, taskId)).thenReturn(before);
        when(taskMapper.updateDueDate(workspaceId, taskId, "2026-08-01")).thenReturn(0);

        assertThrows(
            ResourceNotFoundException.class,
            () -> taskService.reschedule(taskId, "2026-08-01"));

        verifyNoInteractions(referenceService, auditService, notificationChanges);
    }

    @Test
    void completeUsesLockingReadBeforeAssigneeCheck() {
        int workspaceId = 17;
        int taskId = 29;
        Task task = task(taskId, "Complete once");
        User actor = user(31);
        task.setAssignedTo(user(37));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspaceId);
        when(authService.getCurrentUser()).thenReturn(actor);
        when(taskMapper.getTaskByIdForUpdate(workspaceId, taskId)).thenReturn(task);

        assertThrows(ForbiddenException.class, () -> taskService.complete(taskId));

        InOrder order = inOrder(taskMapper);
        order.verify(taskMapper).lockTaskBoard(workspaceId);
        order.verify(taskMapper).listWorkspaceTaskIds(workspaceId);
        order.verify(taskMapper).getTaskByIdForUpdate(workspaceId, taskId);
        verifyNoInteractions(auditService, notificationChanges, ruleTriggers);
    }

    @Test
    void rescheduleDoesNotAcquireBoardRoot() {
        int workspaceId = 17;
        int taskId = 29;
        Task before = task(taskId, "Reschedule only");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspaceId);
        when(taskMapper.getTaskByIdForUpdate(workspaceId, taskId)).thenReturn(before);
        when(taskMapper.updateDueDate(workspaceId, taskId, "2026-08-01")).thenReturn(0);

        assertThrows(
            ResourceNotFoundException.class,
            () -> taskService.reschedule(taskId, "2026-08-01"));

        verify(taskMapper, never()).lockTaskBoard(workspaceId);
    }

    @Test
    void boardMutationRejectsJoinedTransactionWithWrongIsolation() {
        int workspaceId = 17;
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspaceId);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
            Connection.TRANSACTION_REPEATABLE_READ);

        try {
            assertThrows(
                IllegalStateException.class,
                () -> taskService.move(29, "todo", 0));
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        verifyNoInteractions(taskMapper);
    }

    private static Task task(int id, String description) {
        Task task = new Task();
        task.setId(id);
        task.setDescription(description);
        task.setStatus("todo");
        task.setAssignedTo(user(41));
        return task;
    }

    private static User user(int id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
