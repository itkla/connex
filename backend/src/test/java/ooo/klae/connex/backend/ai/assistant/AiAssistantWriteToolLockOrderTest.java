package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Execution;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Outcome;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.PrincipalRequest;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Pins the order the write framework acquires its locks in, for both tiers.
 *
 * <p>The documented order in {@code docs/backend/LOCKING.md} is this one: the locked authorization
 * roots, the session root, the tool-call row, then — immediate tier only — the turn row, then the
 * task board root, the target row, the restriction fence, the owner-scope gate and only then the
 * write. The concurrency integration test proves contention behaviour but cannot observe this
 * sequence; swapping the turn and tool-call acquisitions, hoisting the authority read after the
 * session root, taking the target before the board, or moving the scope gate after the write turns
 * this red.
 */
class AiAssistantWriteToolLockOrderTest extends AbstractAiAssistantWriteToolTest {

    @Test
    void anImmediateTaskTakesEveryLockInTheFrameworkOrder() throws Exception {
        createdTasksGetId74();
        AiAssistantWriteToolService service = service();
        propose(service, "create_task", "{\"handle\":\"r1\",\"description\":\"Agenda\"}",
                "person", 31);

        service.executeAuto(TURN, TOOL_CALL_ID, result -> { });

        InOrder order = inOrder(
                workspaceService, chatMapper, taskService, personService, restrictionEpoch);
        order.verify(workspaceService).lockAndRequirePermissionsSnapshot(
                TURN.workspaceId(), Map.of(TURN.userId(), Set.of(Permission.AI_USE)));
        order.verify(chatMapper).getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId());
        order.verify(chatMapper).getToolCallBySessionForUpdate(
                TURN.workspaceId(), TURN.sessionId(), TOOL_CALL_ID);
        order.verify(chatMapper).getTurnByIdForUpdate(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId());
        order.verify(taskService).lockBoardForCreation();
        order.verify(personService).lockProcessablePersonForShare(31);
        order.verify(restrictionEpoch).retainReadFenceUntilTransactionCompletionIfCurrent(
                TURN.workspaceId(), TURN.restrictionEpoch());
        order.verify(personService).getPersonById(31);
        order.verify(taskService).create(any(Task.class));
        verify(personService, never()).lockProcessablePersonForUpdate(anyInt());
    }

    @Test
    void anApprovedStageChangeTakesEveryLockInTheFrameworkOrderAndNoTurnRow() throws Exception {
        DealService.LockedStageChange locked = stubStageChange();
        when(dealService.changeStage(locked)).thenReturn(deal(6));
        AiAssistantWriteToolService service = service();
        propose(service, "change_deal_stage", "{\"handle\":\"r1\",\"stage\":\"Proposal\"}",
                "deal", 44);

        service.approve(TURN.sessionId(), TOOL_CALL_ID);

        InOrder order = inOrder(workspaceService, chatMapper, dealService, restrictionEpoch);
        order.verify(chatMapper).getAccessibleSessionById(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId());
        order.verify(workspaceService).lockAndRequirePermissionsSnapshot(
                TURN.workspaceId(), Map.of(TURN.userId(), Set.of(Permission.AI_USE)));
        order.verify(chatMapper).getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId());
        order.verify(chatMapper).getToolCallBySessionForUpdate(
                TURN.workspaceId(), TURN.sessionId(), TOOL_CALL_ID);
        order.verify(dealService).getDealById(44);
        order.verify(dealService).lockStageChangeRowsForUpdate(44, 6);
        order.verify(restrictionEpoch).retainReadFenceUntilTransactionCompletionIfCurrent(
                TURN.workspaceId(), TURN.restrictionEpoch());
        order.verify(dealService).getDealById(44);
        order.verify(dealService).changeStage(locked);
        verify(chatMapper, never()).getTurnByIdForUpdate(anyInt(), anyInt(), anyInt());
        verify(dealService, never()).lockDealForUpdate(anyInt());
    }

    @Test
    void anApprovalLocksTheDeclaredPrincipalsAndHandsTheSameObjectsToTheWrite() throws Exception {
        PrincipalRequest owner = new PrincipalRequest(21, "Grace Hopper");
        AtomicReference<Execution> applied = new AtomicReference<>();
        AiAssistantChangeDealStageWriteTool namingTool =
                new AiAssistantChangeDealStageWriteTool(dealService, pipelineService) {
                    @Override
                    public List<PrincipalRequest> principals(
                            AiAssistantWriteToolRequest request) {
                        return List.of(owner);
                    }

                    @Override
                    public Outcome apply(Execution execution) {
                        applied.set(execution);
                        return super.apply(execution);
                    }
                };
        DealService.LockedStageChange locked = stubStageChange();
        when(dealService.changeStage(locked)).thenReturn(deal(6));
        AiAssistantWriteToolService service = service(List.of(createTaskTool(), namingTool));
        propose(service, "change_deal_stage", "{\"handle\":\"r1\",\"stage\":\"Proposal\"}",
                "deal", 44);

        service.approve(TURN.sessionId(), TOOL_CALL_ID);

        InOrder order = inOrder(workspaceService, chatMapper);
        order.verify(workspaceService).lockAndRequirePermissionsSnapshot(
                TURN.workspaceId(),
                Map.of(TURN.userId(), Set.of(Permission.AI_USE), 21, Set.of()));
        order.verify(chatMapper).getSessionByIdForUpdate(
                TURN.workspaceId(), TURN.userId(), TURN.sessionId());
        assertEquals(1, applied.get().principals().size());
        assertSame(owner, applied.get().principals().getFirst());
    }

    @Test
    void anImmediateToolThatNamesAPrincipalIsRefusedBeforeAnyRecordLock() throws Exception {
        AiAssistantCreateTaskWriteTool namingTool =
                new AiAssistantCreateTaskWriteTool(taskService, dateResolver, objectMapper) {
                    @Override
                    public List<PrincipalRequest> principals(
                            AiAssistantWriteToolRequest request) {
                        return List.of(new PrincipalRequest(21, "Grace Hopper"));
                    }
                };
        AiAssistantWriteToolService service = service(List.of(namingTool, stageTool()));
        propose(service, "create_task", "{\"handle\":\"r1\",\"description\":\"Agenda\"}",
                "person", 31);

        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> service.executeAuto(TURN, TOOL_CALL_ID, result -> { }));

        assertEquals("An immediate assistant tool cannot name a principal", refused.getMessage());
        verify(taskService, never()).lockBoardForCreation();
        verify(personService, never()).lockProcessablePersonForShare(anyInt());
        verify(taskService, never()).create(any(Task.class));
    }

    @Test
    void aDealTargetedTaskLocksTheDealForUpdateAfterTheBoard() throws Exception {
        createdTasksGetId74();
        when(dealService.lockDealForUpdate(44)).thenReturn(new Deal());
        AiAssistantWriteToolService service = service();
        propose(service, "create_task", "{\"handle\":\"r1\",\"description\":\"Agenda\"}",
                "deal", 44);

        service.executeAuto(TURN, TOOL_CALL_ID, result -> { });

        InOrder order = inOrder(taskService, dealService);
        order.verify(taskService).lockBoardForCreation();
        order.verify(dealService).lockDealForUpdate(44);
        order.verify(dealService).getDealById(44);
        order.verify(taskService).create(any(Task.class));
        verify(personService, never()).lockProcessablePersonForShare(anyInt());
    }
}
