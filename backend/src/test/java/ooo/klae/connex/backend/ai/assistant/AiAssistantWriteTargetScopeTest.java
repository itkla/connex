package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.DealService;

/**
 * The owner-scope target gate is a real framework step, not prose.
 *
 * <p>After every lock is held and before any tool runs, the framework reads the target through the
 * member-scoped domain getters. A target the actor's scope cannot see refuses there, with the
 * tool's own write never invoked and no result recorded. Dropping the gate from the ordered driver
 * turns every case here red, while every lock-order and permission test stays green.
 *
 * <p>These mocks have no {@code SqlSession}, so they pin that the gate step exists and where it
 * sits, not that its read reaches committed state. The stage case's visible-then-refused stubbing
 * stands for a deal that left the actor's scope between the tool's pre-lock read and the gate;
 * {@code AiAssistantWriteTargetGateCacheIntegrationTest} proves against MySQL that the target lock
 * flushes the first-level cache, so the gate's re-read is not answered with the pre-lock row.
 */
class AiAssistantWriteTargetScopeTest extends AbstractAiAssistantWriteToolTest {

    @Test
    void anImmediateTaskOnAPersonOutsideTheActorsScopeIsNeverCreated() throws Exception {
        when(personService.getPersonById(31))
                .thenThrow(new ResourceNotFoundException("Person not found"));
        AiAssistantWriteToolService service = service();
        propose(service, "create_task", "{\"handle\":\"r1\",\"description\":\"Agenda\"}",
                "person", 31);

        ResourceNotFoundException refused = assertThrows(
                ResourceNotFoundException.class,
                () -> service.executeAuto(TURN, TOOL_CALL_ID, result -> { }));

        assertEquals("Person not found", refused.getMessage());
        verify(personService).lockProcessablePersonForShare(31);
        verify(taskService, never()).create(any(Task.class));
        verify(chatMapper, never()).updateToolCall(
                anyInt(), anyInt(), anyInt(), any(), any(), anyInt());
    }

    @Test
    void anImmediateTaskOnADealOutsideTheActorsScopeIsNeverCreated() throws Exception {
        when(dealService.getDealById(44))
                .thenThrow(new ResourceNotFoundException("Deal not found"));
        AiAssistantWriteToolService service = service();
        propose(service, "create_task", "{\"handle\":\"r1\",\"description\":\"Agenda\"}",
                "deal", 44);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.executeAuto(TURN, TOOL_CALL_ID, result -> { }));

        verify(dealService).lockDealForUpdate(44);
        verify(taskService, never()).create(any(Task.class));
    }

    @Test
    void anApprovedStageChangeOnADealThatLeftTheActorsScopeIsNeverApplied() throws Exception {
        DealService.LockedStageChange locked = stubStageChange();
        when(dealService.getDealById(44))
                .thenReturn(deal(5))
                .thenThrow(new ResourceNotFoundException("Deal not found"));
        AiAssistantWriteToolService service = service();
        propose(service, "change_deal_stage", "{\"handle\":\"r1\",\"stage\":\"Proposal\"}",
                "deal", 44);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));

        verify(dealService).lockStageChangeRowsForUpdate(44, 6);
        verify(dealService, never()).changeStage(locked);
        verify(chatMapper, never()).updateToolCall(
                eq(TURN.workspaceId()), eq(TURN.userMessageId()), eq(TOOL_CALL_ID),
                eq("executed"), any(), eq(TURN.userId()));
    }

    @Test
    void anImmediateMeetingWithAPersonOutsideTheActorsScopeReadsNoCalendarAndIsNeverLogged()
            throws Exception {
        person31IsProcessable();
        when(personService.getPersonById(31))
                .thenThrow(new ResourceNotFoundException("Person not found"));
        AiAssistantWriteToolService service = service();
        propose(service, "create_activity",
                "{\"handle\":\"r1\",\"type\":\"meeting\",\"subject\":\"Planning\","
                        + "\"start\":\"9:00am next Thursday\"}",
                "person", 31);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.executeAuto(TURN, TOOL_CALL_ID, result -> { }));

        verify(personService).lockProcessablePersonForUpdate(31);
        verify(activityService, never()).getActivitiesByPersonIdInWindow(
                anyInt(), any(), any(), anyInt());
        verify(activityService, never()).create(any(Activity.class));
        verify(chatMapper, never()).updateToolCall(
                anyInt(), anyInt(), anyInt(), any(), any(), anyInt());
    }

    @Test
    void anImmediateNoteOnADealOutsideTheActorsScopeIsNeverWritten() throws Exception {
        when(dealService.getDealById(44))
                .thenThrow(new ResourceNotFoundException("Deal not found"));
        AiAssistantWriteToolService service = service();
        propose(service, "create_note", "{\"handle\":\"r1\",\"content\":\"Shared follow-up\"}",
                "deal", 44);

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.executeAuto(TURN, TOOL_CALL_ID, result -> { }));

        verify(dealService).lockDealForUpdate(44);
        verify(noteService, never()).create(any(Note.class));
        verify(chatMapper, never()).updateToolCall(
                anyInt(), anyInt(), anyInt(), any(), any(), anyInt());
    }

    @Test
    void theScheduleReadAToolIsHandedIsBoundToItsOwnPersonTargetAndRefusesADeal()
            throws Exception {
        AiAssistantCreateActivityWriteTool probing = new AiAssistantCreateActivityWriteTool(
                activityService, dateResolver, objectMapper) {
            @Override
            public Outcome apply(Execution execution) {
                execution.scheduleConflicts().find(
                        LocalDateTime.of(2026, 3, 12, 13, 0),
                        LocalDateTime.of(2026, 3, 12, 14, 0));
                return super.apply(execution);
            }
        };
        AiAssistantWriteToolService service = framework(
                List.of(createTaskTool(), stageTool(), probing, createNoteTool()));
        propose(service, "create_activity",
                "{\"handle\":\"r1\",\"type\":\"call\",\"subject\":\"Renewal\","
                        + "\"start\":\"9:00am next Thursday\"}",
                "deal", 44);

        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> service.executeAuto(TURN, TOOL_CALL_ID, result -> { }));

        assertEquals("Assistant schedule read is bound to a person target", refused.getMessage());
        verify(dealService).getDealById(44);
        verify(executorPersonMapper, never()).getPersonById(anyInt(), anyInt());
        verify(activityService, never()).getActivitiesByPersonIdInWindow(
                anyInt(), any(), any(), anyInt());
        verify(activityService, never()).create(any(Activity.class));
    }
}
