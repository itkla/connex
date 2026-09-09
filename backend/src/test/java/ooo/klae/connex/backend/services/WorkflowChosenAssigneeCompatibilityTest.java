package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.notifications.NotificationDelivery;

class WorkflowChosenAssigneeCompatibilityTest {

    private TaskService taskService;
    private RuleActionExecutor executor;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        executor = new RuleActionExecutor(
            taskService,
            mock(ActivityService.class),
            mock(CompanyService.class),
            mock(PersonService.class),
            mock(LeadResponseSlaService.class),
            mock(DealService.class),
            mock(NoteService.class),
            mock(NotificationDelivery.class),
            mock(ooo.klae.connex.backend.mappers.TagMapper.class),
            mock(ooo.klae.connex.backend.mappers.DealDocumentMapper.class),
            mock(CampaignTriggeredSendService.class));
        when(taskService.create(any(Task.class))).thenAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            task.setId(71);
            return task;
        });
    }

    @Test
    void schemaOneKeepsHistoricalAttributionAssigneeBehavior() {
        executor.execute(action(29), context(1));

        assertEquals(17, capturedTask().getAssignedTo().getId());
    }

    @Test
    void schemaTwoUsesTheConfiguredChosenAssignee() {
        executor.execute(action(29), context(2));

        assertEquals(29, capturedTask().getAssignedTo().getId());
    }

    private Task capturedTask() {
        ArgumentCaptor<Task> task = ArgumentCaptor.forClass(Task.class);
        org.mockito.Mockito.verify(taskService).create(task.capture());
        return task.getValue();
    }

    private static RuleAction action(int targetUserId) {
        RuleAction action = new RuleAction();
        action.setType("create_task");
        action.setTitle("Follow up");
        action.setTargetUserId(targetUserId);
        action.setDueInDays(1);
        return action;
    }

    private static WorkflowActionContext context(int schemaVersion) {
        return new WorkflowActionContext(
            7, 31L, "task", "company", 19, 17, 41, Set.of(), schemaVersion);
    }
}
