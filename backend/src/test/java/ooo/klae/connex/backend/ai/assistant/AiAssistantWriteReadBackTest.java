package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Deal;
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
 * keep their exact keys. The mocks here have no {@code SqlSession}, so nothing is claimed about the
 * MyBatis first-level cache.
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
    void aCreatedTaskHasNoRequestedIdentifierAndNeverRecordsADivergence() throws Exception {
        createdTasksGetId74();
        AiAssistantWriteToolService service = service();
        propose(service, "create_task", "{\"handle\":\"r1\",\"description\":\"Agenda\"}",
                "person", 31);

        service.executeAuto(TURN, TOOL_CALL_ID, result -> { });

        JsonNode stored = objectMapper.readTree(capturedExecutedResult());
        assertEquals(List.of("tier", "outcome", "undo"), List.copyOf(stored.propertyNames()));
        assertEquals(74, stored.path("undo").path("entityId").asInt());
    }
}
