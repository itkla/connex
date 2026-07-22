package ooo.klae.connex.backend.services;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    void moveSkipsSuccessSideEffectsWhenWriteLoses() {
        int workspaceId = 17;
        int taskId = 29;
        Task before = task(taskId, "Move once");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspaceId);
        when(taskMapper.getTaskByIdForUpdate(workspaceId, taskId)).thenReturn(before);
        when(taskMapper.getTaskIdsInStatusOrdered(workspaceId, "todo"))
            .thenReturn(new ArrayList<>());
        when(taskMapper.moveTask(workspaceId, taskId, "todo", false, 0)).thenReturn(0);

        assertThrows(ResourceNotFoundException.class, () -> taskService.move(taskId, "todo", 0));

        verifyNoInteractions(referenceService, auditService, notificationChanges, ruleTriggers);
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

        verify(taskMapper).getTaskByIdForUpdate(workspaceId, taskId);
        verifyNoInteractions(auditService, notificationChanges, ruleTriggers);
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
