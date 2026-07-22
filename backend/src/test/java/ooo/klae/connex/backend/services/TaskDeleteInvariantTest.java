package ooo.klae.connex.backend.services;

import java.util.Map;

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
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.notifications.NotificationDelivery;

@ExtendWith(MockitoExtension.class)
class TaskDeleteInvariantTest {

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
    void deleteRunsSuccessSideEffectsForWinningDelete() {
        int workspaceId = 17;
        int taskId = 29;
        Task task = new Task();
        task.setId(taskId);
        task.setDescription("Delete once");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspaceId);
        when(taskMapper.getTaskByIdForUpdate(workspaceId, taskId)).thenReturn(task);
        when(taskMapper.delete(workspaceId, taskId)).thenReturn(1);

        taskService.delete(taskId);

        verify(referenceService).deleteReferences(workspaceId, ReferenceService.SOURCE_TASK, taskId);
        verify(auditService).record(
            "task.delete",
            "task",
            taskId,
            task.getDescription(),
            "Deleted task " + task.getDescription(),
            Map.of()
        );
        verify(notificationChanges).publish(workspaceId, "task", taskId);
    }

    @Test
    void deleteSkipsSuccessSideEffectsWhenDeleteLoses() {
        int workspaceId = 17;
        int taskId = 29;
        Task task = new Task();
        task.setId(taskId);
        task.setDescription("Delete once");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(workspaceId);
        when(taskMapper.getTaskByIdForUpdate(workspaceId, taskId)).thenReturn(task);
        when(taskMapper.delete(workspaceId, taskId)).thenReturn(0);

        assertThrows(ResourceNotFoundException.class, () -> taskService.delete(taskId));

        verifyNoInteractions(referenceService, auditService, notificationChanges);
    }
}
