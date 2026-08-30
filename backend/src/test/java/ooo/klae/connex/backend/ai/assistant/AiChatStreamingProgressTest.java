package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;

class AiChatStreamingProgressTest {

    private static AiChatQueuedTurn turn(AiPrivacyMode privacyMode) {
        return new AiChatQueuedTurn(
                7, 11, 13, 17, 19, 1, 23L, false, List.of(), List.of(), privacyMode, true);
    }

    /**
     * A masked turn's requester receives the same demasked answer once the turn settles, so the
     * streamed batches they read on the way there must carry the same names rather than the
     * placeholders that were sent to the provider.
     */
    @Test
    void aMaskedTurnStreamsDemaskedBatchesToItsRequester() {
        AiChatTurnPersistenceService persistenceService =
                mock(AiChatTurnPersistenceService.class);
        when(persistenceService.appendPartialBatch(any(), anyInt(), any()))
                .thenAnswer(call -> ((Integer) call.getArgument(1))
                        + ((String) call.getArgument(2)).length());
        MaskingContext context = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, "Ada Lovelace", context);
        AiChatStreamingProgress progress = new AiChatStreamingProgress(
                turn(AiPrivacyMode.MASKED), persistenceService, context);

        AiChatStreamingProgress.Observer observer = progress.observer(true);
        observer.onContentDelta("{\"text\":\"Ask " + token + " about it.\"}");
        observer.finish("Ask Ada Lovelace about it.");

        ArgumentCaptor<String> batches = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).appendPartialBatch(any(), anyInt(), batches.capture());
        assertEquals("Ask Ada Lovelace about it.", batches.getValue());
        assertTrue(!batches.getValue().contains(token));
    }

    /**
     * Providers commonly deliver a space or a newline as its own delta. The demasker answers a
     * blank input with the empty string, so demasking one would silently run the words together.
     */
    @Test
    void aWhitespaceOnlyDeltaSurvivesDemasking() {
        AiChatTurnPersistenceService persistenceService =
                mock(AiChatTurnPersistenceService.class);
        when(persistenceService.appendPartialBatch(any(), anyInt(), any()))
                .thenAnswer(call -> ((Integer) call.getArgument(1))
                        + ((String) call.getArgument(2)).length());
        MaskingContext context = new MaskingContext();
        AiChatStreamingProgress progress = new AiChatStreamingProgress(
                turn(AiPrivacyMode.MASKED), persistenceService, context);

        AiChatStreamingProgress.Observer observer = progress.observer(true);
        observer.onContentDelta("{\"text\":\"Two");
        observer.onContentDelta(" ");
        observer.onContentDelta("words\"}");
        observer.finish("Two words");

        ArgumentCaptor<String> batches = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).appendPartialBatch(any(), anyInt(), batches.capture());
        assertEquals("Two words", batches.getValue());
    }

    /**
     * A stream stopped at the durable bound is one attempt's state, not the turn's. A malformed
     * attempt whose demasked text crossed the bound is reset and retried, and the repaired
     * attempt must stream — a truncation left standing would swallow every delta it produces and
     * skip the settle-time comparison built to notice exactly that.
     */
    @Test
    void aResetClearsTheTruncationTheFailedAttemptReached() {
        AiChatTurnPersistenceService persistenceService =
                mock(AiChatTurnPersistenceService.class);
        when(persistenceService.appendPartialBatch(any(), anyInt(), any()))
                .thenAnswer(call -> ((Integer) call.getArgument(1))
                        + ((String) call.getArgument(2)).length());
        AiChatStreamingProgress progress = new AiChatStreamingProgress(
                turn(AiPrivacyMode.UNMASKED),
                persistenceService,
                new MaskingContext(AiPrivacyMode.UNMASKED));

        AiChatStreamingProgress.Observer first = progress.observer(true);
        first.onContentDelta("{\"text\":\"" + "x".repeat(17_000));
        progress.reset();

        AiChatStreamingProgress.Observer second = progress.observer(true);
        second.onContentDelta("{\"text\":\"Recovered answer.\"}");
        second.finish("Recovered answer.");

        ArgumentCaptor<String> batches = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).appendPartialBatch(any(), anyInt(), batches.capture());
        assertEquals("Recovered answer.", batches.getValue());
    }

    /**
     * An unmasked turn's context holds no bindings, so demasking it would only rewrite a literal
     * brace pair the model typed into an unknown-reference marker. Its stream stays untouched.
     */
    @Test
    void anUnmaskedTurnStreamsItsTextUntouched() {
        AiChatTurnPersistenceService persistenceService =
                mock(AiChatTurnPersistenceService.class);
        when(persistenceService.appendPartialBatch(any(), anyInt(), any()))
                .thenAnswer(call -> ((Integer) call.getArgument(1))
                        + ((String) call.getArgument(2)).length());
        AiChatStreamingProgress progress = new AiChatStreamingProgress(
                turn(AiPrivacyMode.UNMASKED),
                persistenceService,
                new MaskingContext(AiPrivacyMode.UNMASKED));

        AiChatStreamingProgress.Observer observer = progress.observer(true);
        observer.onContentDelta("{\"text\":\"Braces {{P1}} stay literal.\"}");
        observer.finish("Braces {{P1}} stay literal.");

        ArgumentCaptor<String> batches = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).appendPartialBatch(any(), anyInt(), batches.capture());
        assertEquals("Braces {{P1}} stay literal.", batches.getValue());
    }

}
