package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.ai.AiGenerationTaskResult;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.notifications.AiChatRealtimeDispatcher;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class AiChatTurnTerminalCoordinatorTest {
    private static final AiChatQueuedTurn TURN = new AiChatQueuedTurn(
            7, 11, 13, 17, 19, 1, 23L, true, List.of(), List.of());

    private AiChatTurnPersistenceService persistenceService;
    private AiChatRealtimeDispatcher dispatcher;
    private AiChatTurnTerminalCoordinator coordinator;

    @BeforeEach
    void setUp() {
        TenantWorkScope tenantWorkScope = mock(TenantWorkScope.class);
        persistenceService = mock(AiChatTurnPersistenceService.class);
        dispatcher = mock(AiChatRealtimeDispatcher.class);
        coordinator = new AiChatTurnTerminalCoordinator(
                tenantWorkScope,
                persistenceService,
                dispatcher);
        AtomicReference<AiChatDurableTerminal> durable = new AtomicReference<>(
                new AiChatDurableTerminal("resolved", null, 31));
        when(tenantWorkScope.inWorkspace(
                eq(TURN.workspaceId()),
                org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
                .thenAnswer(invocation -> {
                    Supplier<?> work = invocation.getArgument(1);
                    return work.get();
                });
        when(persistenceService.markTerminal(eq(TURN), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    durable.set(new AiChatDurableTerminal(
                            invocation.getArgument(1), invocation.getArgument(2), 31));
                    return true;
                });
        when(persistenceService.terminalState(TURN)).thenAnswer(invocation -> durable.get());
    }

    @Test
    void capacityAndDeadlinePersistDistinctTerminalsBeforePublishing() {
        coordinator.generationCapacity(TURN);
        coordinator.listener(TURN).onTerminal(
                AiGenerationTaskResult.Outcome.TIMED_OUT, "turn_deadline_exceeded");

        InOrder order = inOrder(persistenceService, dispatcher);
        order.verify(persistenceService).markTerminal(
                TURN, "failed", "generation_capacity");
        order.verify(persistenceService).terminalState(TURN);
        order.verify(dispatcher).sessionNow(
                TURN.workspaceId(), TURN.sessionId(), new AiChatStepFrameDto(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(), 31,
                "terminal", null, "failed", "generation_capacity"));
        order.verify(persistenceService).markTerminal(
                TURN, "timed_out", "turn_deadline_exceeded");
        order.verify(persistenceService).terminalState(TURN);
        order.verify(dispatcher).sessionNow(
                TURN.workspaceId(), TURN.sessionId(), new AiChatStepFrameDto(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(), 31,
                "terminal", null, "timed_out", "turn_deadline_exceeded"));
    }

    @Test
    void loopFailureReasonsRemainDistinctAndInfrastructureReasonsNormalizeInternally() {
        when(persistenceService.markTerminal(
                eq(TURN), eq("failed"), anyString())).thenReturn(true);

        for (String reason : List.of(
                "provider_error",
                "quota_exhausted",
                "budget_exhausted",
                "tool_result_budget_exhausted",
                "org_invocation_quota_exhausted",
                "invocation_capacity_exhausted",
                "malformed_output",
                "schema_repair_failed",
                "image_input_unsupported",
                "attachment_auto_write_blocked",
                "no_progress",
                "agent_backstop_exceeded",
                "step_cap_exceeded",
                "workspace_disabled",
                "internal_error")) {
            coordinator.listener(TURN).onTerminal(
                    AiGenerationTaskResult.Outcome.FAILED, reason);
            verify(persistenceService).markTerminal(
                    TURN, "failed", reason);
        }
        coordinator.listener(TURN).onTerminal(
                AiGenerationTaskResult.Outcome.FAILED, "generation_failed");
        verify(persistenceService, times(2)).markTerminal(
                TURN, "failed", "internal_error");
        coordinator.listener(TURN).onTerminal(
                AiGenerationTaskResult.Outcome.FAILED, "unexpected_failure");
        verify(persistenceService, times(3)).markTerminal(
                TURN, "failed", "internal_error");
    }

    @Test
    void rejectedDurableTimeoutPublishesTheSupersedingResolution() {
        org.mockito.Mockito.doReturn(false).when(persistenceService).markTerminal(
                TURN, "timed_out", "generation_timeout");

        boolean claimed = coordinator.listener(TURN).onTerminal(
                AiGenerationTaskResult.Outcome.TIMED_OUT, "generation_timeout");

        assertFalse(claimed);
        verify(dispatcher).sessionNow(
                TURN.workspaceId(), TURN.sessionId(), new AiChatStepFrameDto(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(), 31,
                "terminal", null, "resolved", null));
    }

    @Test
    void durableResolutionPublishesTheFullyScopedTerminalFrame() {
        boolean claimed = coordinator.listener(TURN).onTerminal(
                AiGenerationTaskResult.Outcome.RESOLVED, null);

        assertTrue(claimed);
        verify(persistenceService).terminalState(TURN);
        verify(dispatcher).sessionNow(
                TURN.workspaceId(), TURN.sessionId(), new AiChatStepFrameDto(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                31, "terminal", null, "resolved", null));
    }
}
