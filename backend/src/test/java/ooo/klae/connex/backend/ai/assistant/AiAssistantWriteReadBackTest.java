package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Authority;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Execution;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.LockedTarget;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Outcome;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.PrincipalRequest;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.ReadBack;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Row;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Target;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteToolRequest.AssignOwner;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
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
 *
 * <p>An owner assignment is read back by the owner id on the record {@code updateOwner} returned,
 * compared with the principal the framework resolved and locked before the write — {@code null}
 * on both sides for a removal — while the stored {@code owner} stays that principal's label.
 *
 * <p>The create-activity and create-note cases pin the framework's comparison and its placement
 * for those tools, not a read-back of stored state: the real {@code ActivityService.create} and
 * {@code NoteService.create} return the bean the tool built, so in production their link cannot
 * diverge, and these stubs rewrite it only to prove the framework would record it if it did.
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

    @Test
    void anOwnerAssignmentReadsTheOwnerIdOffTheReturnedRecordAndKeepsTheResolvedLabel()
            throws Exception {
        stubGraceHopper();
        Company returned = company(21);
        returned.setName("Renamed meanwhile");
        when(companyService.updateOwner(52, 21)).thenReturn(returned);
        AiAssistantWriteToolService service = service();
        propose(service, "assign_owner", "{\"handle\":\"r1\",\"owner\":\" grace hopper \"}",
                "company", 52);

        AiAssistantToolCallDto approved = service.approve(TURN.sessionId(), TOOL_CALL_ID);

        JsonNode stored = objectMapper.readTree(capturedExecutedResult());
        assertEquals(List.of("tier", "approval", "outcome"), List.copyOf(stored.propertyNames()));
        assertEquals(
                "{\"status\":\"executed\",\"recordType\":\"company\",\"owner\":\"Grace Hopper\"}",
                objectMapper.writeValueAsString(stored.get("outcome")));
        assertEquals(stored.get("outcome"), approved.result());
    }

    @Test
    void anOwnerAssignmentThatLandedOnAnotherOwnerRecordsTheDivergenceBesideTheOutcome()
            throws Exception {
        stubGraceHopper();
        when(companyService.updateOwner(52, 21)).thenReturn(company(22));
        AiAssistantWriteToolService service = service();
        AiAssistantPreparedWrite write = propose(
                service, "assign_owner", "{\"handle\":\"r1\",\"owner\":\"Grace Hopper\"}",
                "company", 52);

        AiAssistantToolCallDto approved = service.approve(TURN.sessionId(), TOOL_CALL_ID);

        JsonNode stored = objectMapper.readTree(capturedExecutedResult());
        assertEquals(
                List.of("tier", "approval", "outcome", "verification"),
                List.copyOf(stored.propertyNames()));
        assertEquals(
                "{\"field\":\"ownerId\",\"requested\":21,\"applied\":22}",
                objectMapper.writeValueAsString(stored.get("verification")));
        assertEquals(
                "{\"status\":\"executed\",\"recordType\":\"company\",\"owner\":\"Grace Hopper\"}",
                objectMapper.writeValueAsString(stored.get("outcome")));
        assertFalse(approved.result().has("verification"));
        assertEquals(
                "{\"toolCallId\":29,\"tool\":\"assign_owner\",\"tier\":\"confirm\","
                        + "\"status\":\"executed\",\"outcome\":{\"recordType\":\"company\","
                        + "\"owner\":\"Grace Hopper\"}}",
                objectMapper.writeValueAsString(service.proposalResult(
                        write,
                        new AiAssistantToolProposal(
                                TOOL_CALL_ID, "executed", capturedExecutedResult(), true))
                        .data()));
    }

    @Test
    void anOwnerRemovalThatLeftAnOwnerRecordsTheDivergence() throws Exception {
        when(companyService.updateOwner(52, null)).thenReturn(company(21));
        AiAssistantWriteToolService service = service();
        propose(service, "assign_owner", "{\"handle\":\"r1\",\"owner\":\"unassigned\"}",
                "company", 52);

        service.approve(TURN.sessionId(), TOOL_CALL_ID);

        JsonNode stored = objectMapper.readTree(capturedExecutedResult());
        assertEquals(
                "{\"field\":\"ownerId\",\"requested\":null,\"applied\":21}",
                objectMapper.writeValueAsString(stored.get("verification")));
        assertEquals("unassigned", stored.path("outcome").path("owner").asString());
    }

    @Test
    void anOwnerAssignmentWhoseServiceReturnsNoRecordIsRefusedAndRecordsNothing()
            throws Exception {
        stubGraceHopper();
        AiAssistantWriteToolService service = service();
        propose(service, "assign_owner", "{\"handle\":\"r1\",\"owner\":\"Grace Hopper\"}",
                "company", 52);

        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> service.approve(TURN.sessionId(), TOOL_CALL_ID));

        assertEquals("Assistant owner assignment could not be read back", refused.getMessage());
        verify(chatMapper, never()).updateToolCall(
                anyInt(), anyInt(), anyInt(), any(), any(), anyInt());
    }

    @Test
    void anOwnerToolNeverWritesAMemberTheFrameworkDidNotHandIt() {
        AiAssistantAssignOwnerWriteTool tool = assignOwnerTool();
        Authority authority = new Authority(
                TURN.workspaceId(), TURN.userId(), TOOL_CALL_ID, CLOCK.instant());
        Target target = new Target("company", 52);

        IllegalStateException unresolved = assertThrows(
                IllegalStateException.class,
                () -> tool.apply(execution(
                        authority, target, new AssignOwner("r1", "Grace Hopper"), List.of())));
        IllegalStateException named = assertThrows(
                IllegalStateException.class,
                () -> tool.apply(execution(
                        authority, target, new AssignOwner("r1", " Unassigned "),
                        List.of(new PrincipalRequest(21, "Grace Hopper")))));
        IllegalStateException twoOwners = assertThrows(
                IllegalStateException.class,
                () -> tool.apply(execution(
                        authority, target, new AssignOwner("r1", "Grace Hopper"),
                        List.of(
                                new PrincipalRequest(21, "Grace Hopper"),
                                new PrincipalRequest(22, "Grace Hopper")))));

        assertEquals("Assistant owner assignment was not resolved", unresolved.getMessage());
        assertEquals("Assistant owner removal names a principal", named.getMessage());
        assertEquals("Assistant owner assignment was not resolved", twoOwners.getMessage());
        verify(companyService, never()).updateOwner(anyInt(), any());
    }

    private static Execution execution(
            Authority authority,
            Target target,
            AssignOwner request,
            List<PrincipalRequest> principals) {
        return new Execution(
                authority,
                new Row(target, request),
                principals,
                null,
                new LockedTarget(null, null),
                (startUtc, endUtc) -> {
                    throw new IllegalStateException("No schedule read");
                });
    }

    private void stubGraceHopper() {
        User owner = new User();
        owner.setId(21);
        owner.setDisplayName("Grace Hopper");
        owner.setUsername("ghopper");
        when(workspaceService.getMembers(TURN.workspaceId())).thenReturn(List.of(owner));
    }

    private static Company company(Integer ownerId) {
        Company company = new Company();
        company.setId(52);
        company.setName("Acme Holdings");
        company.setOwnerId(ownerId);
        return company;
    }
}
