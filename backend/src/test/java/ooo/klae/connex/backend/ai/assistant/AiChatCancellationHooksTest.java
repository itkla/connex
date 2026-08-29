package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.masking.MaskingContext;

class AiChatCancellationHooksTest {
    @Test
    void cancellationRunsOnlyTheExactActiveTurnHookOnce() {
        AiChatQueuedTurn turn = new AiChatQueuedTurn(
                7, 11, 13, 17, 19, 1, 23L, false, List.of(), List.of());
        AtomicInteger cancellations = new AtomicInteger();
        AiChatCancellationHooks.Registration registration =
                AiChatCancellationHooks.register(turn, cancellations::incrementAndGet);
        try {
            AiChatCancellationHooks.cancel(7, 13, 18);
            AiChatCancellationHooks.cancel(7, 13, 17);
            AiChatCancellationHooks.cancel(7, 13, 17);

            assertEquals(1, cancellations.get());
        } finally {
            registration.close();
        }
    }

    @Test
    void transportRegistrationRechecksDurableCancellationBeforeSend() {
        AiChatQueuedTurn turn = new AiChatQueuedTurn(
                7, 11, 13, 17, 19, 1, 23L, false, List.of(), List.of(),
                ooo.klae.connex.backend.ai.AiPrivacyMode.UNMASKED, true);
        AiChatTurnPersistenceService persistenceService = mock(
                AiChatTurnPersistenceService.class);
        AiAssistantLoopException cancelled = new AiAssistantLoopException(
                "cancelled", "cancelled");
        doThrow(cancelled).when(persistenceService).requireRunning(turn);
        AtomicInteger cancellations = new AtomicInteger();
        AiChatStreamingProgress.Observer observer = new AiChatStreamingProgress(
                turn, persistenceService, new MaskingContext()).observer(false);

        AiAssistantLoopException thrown = assertThrows(
                AiAssistantLoopException.class,
                () -> observer.onTransportOpen(cancellations::incrementAndGet));

        assertEquals(cancelled, thrown);
        assertEquals(1, cancellations.get());
        verify(persistenceService).requireRunning(turn);
    }
}
