package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Execution;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Outcome;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.ReadBack;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.AiAssistantToolCallDto;
import ooo.klae.connex.backend.services.DealService;
import tools.jackson.databind.JsonNode;

/**
 * Verify-after-write compares the identifier the write produced, never the request echoed back.
 *
 * <p>The stage a deal was moved to is read off the entity {@code DealService.changeStage} returned
 * and compared by id with the stage resolved before the lock, while the stored {@code stage} label
 * stays the name resolved before the lock. A divergence is recorded as a {@code verification}
 * sibling of {@code outcome}, never inside it, so the API response, the card and the model's view
 * keep their exact keys. Every write declares a comparison and a {@code null} on either side is
 * compared like any other value, so no tool can opt out of the check. The mocks here have no
 * {@code SqlSession}, so nothing is claimed about the MyBatis first-level cache.
 */
class AiAssistantWriteReadBackTest extends AbstractAiAssistantWriteToolTest {

    @Test
    void aStageChangeThatLandedOnAnotherStageRecordsTheDivergenceBesideTheOutcome()
            throws Exception {
        DealService.LockedStageChange locked = stubStageChange();
        when(dealService.changeStage(locked)).thenReturn(deal(9));
        AiAssistantWriteToolService service = service();
        AiAssistantPreparedWrite write = propose(
                service, "change_deal_stage", "{\"handle\":\"r1\",\"stage\":\"Proposal\"}",
                "deal", 44);

        AiAssistantToolCallDto approved = service.approve(TURN.sessionId(), TOOL_CALL_ID);

        JsonNode stored = objectMapper.readTree(capturedExecutedResult());
        assertEquals(
                List.of("tier", "approval", "outcome", "verification"),
                List.copyOf(stored.propertyNames()));
        assertEquals(
                "{\"field\":\"stageId\",\"requested\":6,\"applied\":9}",
                objectMapper.writeValueAsString(stored.get("verification")));
        assertEquals(
                "{\"status\":\"executed\",\"recordType\":\"deal\",\"stage\":\"Proposal\"}",
                objectMapper.writeValueAsString(stored.get("outcome")));
        assertEquals(stored.get("outcome"), approved.result());
        assertFalse(approved.result().has("verification"));
        assertEquals(
                "{\"toolCallId\":29,\"tool\":\"change_deal_stage\",\"tier\":\"confirm\","
                        + "\"status\":\"executed\",\"outcome\":{\"recordType\":\"deal\","
                        + "\"stage\":\"Proposal\"}}",
                objectMapper.writeValueAsString(service.proposalResult(
                        write,
                        new AiAssistantToolProposal(
                                TOOL_CALL_ID, "executed", capturedExecutedResult(), true))
                        .data()));
    }

    @Test
    void aStageChangeThatLandedWhereResolvedRecordsNoVerification() throws Exception {
        DealService.LockedStageChange locked = stubStageChange();
        when(dealService.changeStage(locked)).thenReturn(deal(6));
        AiAssistantWriteToolService service = service();
        propose(service, "change_deal_stage", "{\"handle\":\"r1\",\"stage\":\"Proposal\"}",
                "deal", 44);

        service.approve(TURN.sessionId(), TOOL_CALL_ID);

        assertEquals(
                List.of("tier", "approval", "outcome"),
                List.copyOf(objectMapper.readTree(capturedExecutedResult()).propertyNames()));
    }

    @Test
    void theStageLabelIsTheNameResolvedBeforeTheLockNotOneReadBackOffTheDeal() throws Exception {
        DealService.LockedStageChange locked = stubStageChange();
        Deal renamedMeanwhile = deal(6);
        renamedMeanwhile.setName("Renamed deal");
        when(dealService.changeStage(locked)).thenReturn(renamedMeanwhile);
        AiAssistantWriteToolService service = service();
        propose(service, "change_deal_stage", "{\"handle\":\"r1\",\"stage\":\" proposal \"}",
                "deal", 44);

        service.approve(TURN.sessionId(), TOOL_CALL_ID);

        assertEquals(
                "Proposal",
                objectMapper.readTree(capturedExecutedResult())
                        .path("outcome").path("stage").asString());
    }

    @Test
    void aCreatedTaskLinkedToItsTargetRecordsNoVerification() throws Exception {
        createdTasksGetId74();
        AiAssistantWriteToolService service = service();
        propose(service, "create_task", "{\"handle\":\"r1\",\"description\":\"Agenda\"}",
                "person", 31);

        service.executeAuto(TURN, TOOL_CALL_ID, result -> { });

        JsonNode stored = objectMapper.readTree(capturedExecutedResult());
        assertEquals(List.of("tier", "outcome", "undo"), List.copyOf(stored.propertyNames()));
        assertEquals(74, stored.path("undo").path("entityId").asInt());
    }

    @Test
    void aCreatedTaskTheServiceReturnedUnlinkedRecordsTheDivergence() throws Exception {
        doAnswer(invocation -> {
            Task created = invocation.getArgument(0);
            created.setId(74);
            created.setPerson(null);
            return created;
        }).when(taskService).create(any(Task.class));
        AiAssistantWriteToolService service = service();
        propose(service, "create_task", "{\"handle\":\"r1\",\"description\":\"Agenda\"}",
                "person", 31);

        service.executeAuto(TURN, TOOL_CALL_ID, result -> { });

        JsonNode stored = objectMapper.readTree(capturedExecutedResult());
        assertEquals(
                List.of("tier", "outcome", "undo", "verification"),
                List.copyOf(stored.propertyNames()));
        assertEquals(
                "{\"field\":\"personId\",\"requested\":31,\"applied\":null}",
                objectMapper.writeValueAsString(stored.get("verification")));
    }

    @Test
    void aWriteThatRequestedNoValueButAppliedOneRecordsTheDivergence() throws Exception {
        DealService.LockedStageChange locked = stubStageChange();
        when(dealService.changeStage(locked)).thenReturn(deal(9));
        AiAssistantChangeDealStageWriteTool requestsNothing =
                new AiAssistantChangeDealStageWriteTool(dealService, pipelineService) {
                    @Override
                    public Outcome apply(Execution execution) {
                        Outcome applied = super.apply(execution);
                        return new Outcome(
                                applied.data(),
                                applied.inverse(),
                                new ReadBack("stageId", null, applied.readBack().applied()));
                    }
                };
        AiAssistantWriteToolService service = service(List.of(createTaskTool(), requestsNothing));
        propose(service, "change_deal_stage", "{\"handle\":\"r1\",\"stage\":\"Proposal\"}",
                "deal", 44);

        service.approve(TURN.sessionId(), TOOL_CALL_ID);

        assertEquals(
                "{\"field\":\"stageId\",\"requested\":null,\"applied\":9}",
                objectMapper.writeValueAsString(
                        objectMapper.readTree(capturedExecutedResult()).get("verification")));
    }

    @Test
    void aCreatedActivityTheServiceReturnedUnlinkedRecordsTheDivergence() throws Exception {
        doAnswer(invocation -> {
            Activity created = invocation.getArgument(0);
            created.setId(73);
            created.setDeal(null);
            return created;
        }).when(activityService).create(any(Activity.class));
        AiAssistantWriteToolService service = service();
        propose(service, "create_activity",
                "{\"handle\":\"r1\",\"type\":\"call\",\"subject\":\"Renewal\","
                        + "\"start\":\"9:00am next Thursday\"}",
                "deal", 44);

        service.executeAuto(TURN, TOOL_CALL_ID, result -> { });

        JsonNode stored = objectMapper.readTree(capturedExecutedResult());
        assertEquals(
                List.of("tier", "outcome", "undo", "verification"),
                List.copyOf(stored.propertyNames()));
        assertEquals(
                "{\"field\":\"dealId\",\"requested\":44,\"applied\":null}",
                objectMapper.writeValueAsString(stored.get("verification")));
        assertEquals("call", stored.path("outcome").path("type").asString());
    }

    @Test
    void aCreatedNoteTheServiceLinkedElsewhereRecordsTheDivergence() throws Exception {
        doAnswer(invocation -> {
            Note created = invocation.getArgument(0);
            created.setId(75);
            Person other = new Person();
            other.setId(32);
            created.setPerson(other);
            return created;
        }).when(noteService).create(any(Note.class));
        AiAssistantWriteToolService service = service();
        propose(service, "create_note", "{\"handle\":\"r1\",\"content\":\"Shared follow-up\"}",
                "person", 31);

        service.executeAuto(TURN, TOOL_CALL_ID, result -> { });

        JsonNode stored = objectMapper.readTree(capturedExecutedResult());
        assertEquals(
                List.of("tier", "outcome", "undo", "verification"),
                List.copyOf(stored.propertyNames()));
        assertEquals(
                "{\"field\":\"personId\",\"requested\":31,\"applied\":32}",
                objectMapper.writeValueAsString(stored.get("verification")));
        assertEquals(
                List.of("status", "recordType", "visibility"),
                List.copyOf(stored.path("outcome").propertyNames()));
    }
}
