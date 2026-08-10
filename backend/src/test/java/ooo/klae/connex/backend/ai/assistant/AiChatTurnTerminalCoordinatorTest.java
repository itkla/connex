package ooo.klae.connex.backend.ai.assistant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
            7, 11, 13, 17, 19, 23, List.of());

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
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                "failed", "generation_capacity")).thenReturn(true);
        when(persistenceService.markTerminal(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                "timed_out", "generation_timeout")).thenReturn(true);
    }

    @Test
    void capacityAndDeadlinePersistDistinctTerminalsBeforePublishing() {
        coordinator.generationCapacity(TURN);
        coordinator.listener(TURN).onTerminal(
                AiGenerationTaskResult.Outcome.TIMED_OUT, "generation_timeout");

        InOrder order = inOrder(persistenceService, publisher);
        order.verify(persistenceService).markTerminal(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                "failed", "generation_capacity");
        order.verify(publisher).send(TURN.userId(), new AiChatStepFrameDto(
                TURN.turnId(), 0, "terminal", null, "failed", "generation_capacity"));
        order.verify(persistenceService).markTerminal(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                "timed_out", "generation_timeout");
        order.verify(publisher).send(TURN.userId(), new AiChatStepFrameDto(
                TURN.turnId(), 0, "terminal", null, "timed_out", "generation_timeout"));
    }

    @Test
    void loopFailureReasonsRemainDistinctAndUnknownInfrastructureReasonsNormalize() {
        when(persistenceService.markTerminal(
                eq(TURN.workspaceId()), eq(TURN.sessionId()), eq(TURN.turnId()),
                eq("failed"), anyString())).thenReturn(true);

        for (String reason : List.of(
                "provider_error",
                "quota_exhausted",
                "malformed_output",
                "step_cap_exceeded")) {
            coordinator.listener(TURN).onTerminal(
                    AiGenerationTaskResult.Outcome.FAILED, reason);
            verify(persistenceService).markTerminal(
                    TURN.workspaceId(), TURN.sessionId(), TURN.turnId(), "failed", reason);
        }
        coordinator.listener(TURN).onTerminal(
                AiGenerationTaskResult.Outcome.FAILED, "generation_failed");
        verify(persistenceService, times(2)).markTerminal(
                TURN.workspaceId(), TURN.sessionId(), TURN.turnId(),
                "failed", "provider_error");
    }
}
