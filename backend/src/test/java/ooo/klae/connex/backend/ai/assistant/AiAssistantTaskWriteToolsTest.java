package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.TaskService;
import ooo.klae.connex.backend.tenant.Permission;

/** Task proposals preserve semantics, authorization, lock order and verified delegate outcomes. */
class AiAssistantTaskWriteToolsTest extends AbstractAiAssistantWriteToolTest {
    private Task task() {
        Task task = new Task();
        task.setId(73);
        task.setDescription("Send the agenda");
        task.setStatus("todo");
        task.setDueDate("2026-10-10");
        User assignee = new User();
        assignee.setId(TURN.userId());
        task.setAssignedTo(assignee);
        when(taskService.getTaskById(73)).thenReturn(task);
        when(taskService.assistantStateVersion(73)).thenReturn(TaskService.assistantStateVersion(task));
        when(taskService.lockTaskForUpdate(73)).thenReturn(task);
        return task;
    }

    @Test
    void completionPinsCanonicalStateAndLocksBoardBeforeRowAndScopeGate() throws Exception {
        Task task = task();
        when(taskService.complete(73)).thenReturn(task);
        AiAssistantWriteToolService service = service();
        var proposal = propose(service, "complete_task", "{\"handle\":\"t1\"}", "task", 73);
        var stored = objectMapper.readTree(proposal.argumentsJson());
        assertEquals("t1", stored.path("request").path("handle").asString());
        assertEquals(TaskService.assistantStateVersion(task), stored.path("targetVersion").asString());
        verify(taskService, never()).complete(anyInt());
        clearInvocations(taskService);
        var applied = service.approve(TURN.sessionId(), TOOL_CALL_ID);
        InOrder order = inOrder(taskService);
        order.verify(taskService).lockBoardForUpdate();
        order.verify(taskService).lockTaskForUpdate(73);
        order.verify(taskService).getTaskById(73);
        order.verify(taskService).complete(73);
        assertFalse(applied.undoAvailable());
        assertEquals("task", applied.result().path("recordType").asString());
        assertFalse(applied.result().has("taskId"));
    }

    @Test
    void positionAndTimestampDriftDoNotInvalidateRescheduleAndItTakesNoBoard() throws Exception {
        Task task = task();
        when(taskService.reschedule(73, "2026-10-15")).thenReturn(task);
        AiAssistantWriteToolService service = service();
        propose(service, "reschedule_task", "{\"handle\":\"t1\",\"due_date\":\"2026-10-15\"}", "task", 73);
        task.setPosition(99);
        task.setUpdatedAt("2026-10-01 12:00:00");
        clearInvocations(taskService);
        service.approve(TURN.sessionId(), TOOL_CALL_ID);
        InOrder order = inOrder(taskService);
        order.verify(taskService).lockTaskForUpdate(73);
        order.verify(taskService).getTaskById(73);
        order.verify(taskService).reschedule(73, "2026-10-15");
        verify(taskService, never()).lockBoardForUpdate();
        verify(taskService, never()).lockBoardForCreation();
    }

    @Test
    void reassignmentRefusesBeforeDelegateEvenIfTimestampDidNotMove() throws Exception {
        Task task = task();
        AiAssistantWriteToolService service = service();
        propose(service, "complete_task", "{\"handle\":\"t1\"}", "task", 73);
        User other = new User();
        other.setId(99);
        task.setAssignedTo(other);
        ConflictException refusal = assertThrows(ConflictException.class,
                () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        assertEquals("Assistant proposal target changed", refusal.getMessage());
        verify(taskService, never()).complete(anyInt());
    }

    @Test
    void taskPermissionAndPostLockScopeGateRefuseBeforeApply() throws Exception {
        task();
        AiAssistantWriteToolService service = service();
        propose(service, "complete_task", "{\"handle\":\"t1\"}", "task", 73);
        Set<Permission> permissions = EnumSet.allOf(Permission.class);
        permissions.remove(Permission.TASK_UPDATE);
        when(authority.effectiveFor(TURN.userId())).thenReturn(permissions);
        ForbiddenException refusal = assertThrows(ForbiddenException.class,
                () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        assertEquals("Requires the TASK_UPDATE permission in this workspace", refusal.getMessage());
        verify(taskService, never()).lockTaskForUpdate(anyInt());
        when(authority.effectiveFor(TURN.userId())).thenReturn(EnumSet.allOf(Permission.class));
        when(taskService.getTaskById(73)).thenThrow(new ResourceNotFoundException("Task unavailable"));
        assertThrows(ResourceNotFoundException.class,
                () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));
        verify(taskService, never()).complete(anyInt());
    }

    @Test
    void aReplayedProposalKeepsItsOriginalVersionWithoutReadingTheChangedTask() throws Exception {
        task();
        AiAssistantWriteToolService service = service();
        var first = propose(service, "complete_task", "{\"handle\":\"t1\"}", "task", 73);
        clearInvocations(taskService);
        AiChatResourceRegistry resources = new AiChatResourceRegistry();
        resources.registerTask(73);
        var replay = service.prepareReplay("complete_task", objectMapper.readTree("{\"handle\":\"t1\"}"),
                resources, TURN.restrictionEpoch(), first.argumentsJson());
        assertEquals(first.argumentsJson(), replay.argumentsJson());
        verifyNoInteractions(taskService);
    }

    @Test
    void invalidCalendarDatesNeverCreateAnApprovableProposal() throws Exception {
        task();
        AiAssistantWriteToolService service = service();
        AiAssistantLoopException refusal = assertThrows(AiAssistantLoopException.class,
                () -> propose(service, "reschedule_task",
                        "{\"handle\":\"t1\",\"due_date\":\"2026-02-30\"}", "task", 73));
        assertEquals("invalid_tool_arguments", refusal.detailReason());
        verify(taskService, never()).reschedule(anyInt(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void taskReadBackRecordsDivergenceWithoutLeakingIdentifiersToTheOutcome() throws Exception {
        task();
        Task wrong = new Task();
        wrong.setId(74);
        wrong.setDescription("Send the agenda");
        wrong.setCompleted(true);
        when(taskService.complete(73)).thenReturn(wrong);
        AiAssistantWriteToolService service = service();
        propose(service, "complete_task", "{\"handle\":\"t1\"}", "task", 73);
        var applied = service.approve(TURN.sessionId(), TOOL_CALL_ID);
        assertTrue(objectMapper.readTree(capturedExecutedResult()).has("verification"));
        assertFalse(applied.result().has("verification"));
        assertFalse(applied.result().has("taskId"));
    }
}
