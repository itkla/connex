package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import ooo.klae.connex.backend.ai.AiGenerationTaskResult;
import ooo.klae.connex.backend.dto.AiChatStepFrameDto;
import ooo.klae.connex.backend.notifications.AiChatRealtimePublisher;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class AiChatTurnTerminalCoordinatorTest {
    private static final AiChatQueuedTurn TURN = new AiChatQueuedTurn(
            7, 11, 13, 17, 19, 1, 23L, true, List.of());

    private AiChatTurnPersistenceService persistenceService;
    private AiChatRealtimePublisher publisher;
    private AiChatTurnTerminalCoordinator coordinator;

    @BeforeEach
    void setUp() {
        TenantWorkScope tenantWorkScope = mock(TenantWorkScope.class);
        persistenceService = mock(AiChatTurnPersistenceService.class);
        publisher = mock(AiChatRealtimePublisher.class);
        var beans = new StaticListableBeanFactory();
        beans.addBean("publisher", publisher);
        coordinator = new AiChatTurnTerminalCoordinator(
                tenantWorkScope,
                persistenceService,
                beans.getBeanProvider(AiChatRealtimePublisher.class));
        when(tenantWorkScope.inWorkspace(
                eq(TURN.workspaceId()),
                org.mockito.ArgumentMatchers.<Supplier<Boolean>>any()))
                .thenAnswer(invocation -> {
                    Supplier<Boolean> work = invocation.getArgument(1);
                    return work.get();
                });
        when(persistenceService.markTerminal(
                TURN, "failed", "generation_capacity")).thenReturn(true);
        when(persistenceService.markTerminal(
                TURN, "timed_out", "generation_timeout")).thenReturn(true);
    }

    @Test
    void capacityAndDeadlinePersistDistinctTerminalsBeforePublishing() {
        coordinator.generationCapacity(TURN);
        coordinator.listener(TURN).onTerminal(
                AiGenerationTaskResult.Outcome.TIMED_OUT, "generation_timeout");

        InOrder order = inOrder(persistenceService, publisher);
        order.verify(persistenceService).markTerminal(
                TURN, "failed", "generation_capacity");
        order.verify(publisher).send(TURN.userId(), new AiChatStepFrameDto(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(), 0,
                "terminal", null, "failed", "generation_capacity"));
        order.verify(persistenceService).markTerminal(
                TURN, "timed_out", "generation_timeout");
        order.verify(publisher).send(TURN.userId(), new AiChatStepFrameDto(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(), 0,
                "terminal", null, "timed_out", "generation_timeout"));
    }

    @Test
    void loopFailureReasonsRemainDistinctAndInfrastructureReasonsNormalizeInternally() {
        when(persistenceService.markTerminal(
                eq(TURN), eq("failed"), anyString())).thenReturn(true);

        for (String reason : List.of(
                "provider_error",
                "quota_exhausted",
                "malformed_output",
                "step_cap_exceeded",
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
    void rejectedDurableTimeoutDoesNotPublishOrWinTheGenerationTerminal() {
        when(persistenceService.markTerminal(
                TURN, "timed_out", "generation_timeout")).thenReturn(false);

        boolean claimed = coordinator.listener(TURN).onTerminal(
                AiGenerationTaskResult.Outcome.TIMED_OUT, "generation_timeout");

        assertFalse(claimed);
        verify(publisher, never()).send(eq(TURN.userId()), any());
    }

    @Test
    void durableResolutionPublishesTheFullyScopedTerminalFrame() {
        boolean claimed = coordinator.listener(TURN).onTerminal(
                AiGenerationTaskResult.Outcome.RESOLVED, null);

        assertTrue(claimed);
        verifyNoInteractions(persistenceService);
        verify(publisher).send(TURN.userId(), new AiChatStepFrameDto(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                0, "terminal", null, "resolved", null));
    }
}
