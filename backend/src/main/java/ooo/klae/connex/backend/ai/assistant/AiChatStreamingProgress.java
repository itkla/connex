package ooo.klae.connex.backend.ai.assistant;

import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.masking.Demasker;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.masking.SpecialCareTextScreen;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;

/** Batches decoded terminal text into durable UTF-16-sequenced realtime frames. */
final class AiChatStreamingProgress {
    private static final int BATCH_CHARACTERS = 256;
    private static final long CHECK_NANOS = java.time.Duration.ofMillis(250).toNanos();

    private final AiChatQueuedTurn turn;
    private final AiChatTurnPersistenceService persistenceService;
    private final MaskingContext maskingContext;
    private final boolean demasking;
    private final StringBuilder durable = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private long lastCheckNanos = System.nanoTime();
    private boolean excluded;

    /**
     * Creates the streaming batcher for one turn.
     *
     * <p>A masked turn demasks each batch here, at the choke point, because the requester is
     * entitled to the same demasked answer they would receive when the turn settles — masking
     * governs what leaves for the provider, not what returns to the member who asked. An unmasked
     * turn deliberately skips demasking: its context holds no bindings, and running the demasker
     * anyway would rewrite a literal brace pair the model typed into an unknown-reference marker.
     */
    AiChatStreamingProgress(
            AiChatQueuedTurn turn,
            AiChatTurnPersistenceService persistenceService,
            MaskingContext maskingContext) {
        this.turn = java.util.Objects.requireNonNull(turn, "turn");
        this.maskingContext = java.util.Objects.requireNonNull(maskingContext, "maskingContext");
        this.demasking = maskingContext.privacyMode() != AiPrivacyMode.UNMASKED;
        this.persistenceService = java.util.Objects.requireNonNull(
                persistenceService, "persistenceService");
    }

    Observer observer(boolean nativeTools) {
        return new Observer(nativeTools
                ? AiAssistantTextDeltaProjector.Shape.NATIVE_FINAL
                : AiAssistantTextDeltaProjector.Shape.JSON_REACT);
    }

    private void acceptDecoded(String text) {
        if (excluded) {
            return;
        }
        pending.append(demasking ? Demasker.demask(text, maskingContext).text() : text);
        // Screening runs on the demasked text for a masked turn, which is the text the member
        // actually sees. Screening the masked form would be close to vacuous: every value that
        // could carry special-care content has already become a placeholder by then.
        if (SpecialCareTextScreen.screen(durable.toString() + pending).excluded()) {
            excluded = true;
            pending.setLength(0);
            return;
        }
        if (pending.length() >= BATCH_CHARACTERS) {
            flush();
        }
    }

    private void checkpoint() {
        long now = System.nanoTime();
        if (now - lastCheckNanos < CHECK_NANOS) {
            return;
        }
        if (pending.isEmpty()) {
            persistenceService.requireRunning(turn);
        } else {
            flush();
        }
        lastCheckNanos = now;
    }

    private void flush() {
        if (pending.isEmpty()) {
            return;
        }
        String batch = pending.toString();
        int nextOffset = persistenceService.appendPartialBatch(
                turn, durable.length(), batch);
        durable.append(batch);
        pending.setLength(0);
        if (durable.length() != nextOffset) {
            throw new IllegalStateException("Assistant stream offset diverged");
        }
        lastCheckNanos = System.nanoTime();
    }

    void reset() {
        persistenceService.resetPartialContent(turn, durable.length());
        durable.setLength(0);
        pending.setLength(0);
        excluded = false;
        lastCheckNanos = System.nanoTime();
    }

    final class Observer implements AiProviderStreamObserver {
        private final AiAssistantTextDeltaProjector projector;
        private AiChatCancellationHooks.Registration registration;

        private Observer(AiAssistantTextDeltaProjector.Shape shape) {
            projector = new AiAssistantTextDeltaProjector(shape, AiChatStreamingProgress.this::acceptDecoded);
        }

        @Override
        public void onReasoningMode(AiReasoningMode reasoningMode) {
            projector.setReasoningMode(reasoningMode);
        }

        @Override
        public void onTransportOpen(Runnable cancellation) {
            onTransportClosed();
            registration = AiChatCancellationHooks.register(turn, cancellation);
            try {
                persistenceService.requireRunning(turn);
            } catch (RuntimeException exception) {
                cancellation.run();
                onTransportClosed();
                throw exception;
            }
        }

        @Override
        public void onTransportClosed() {
            if (registration != null) {
                registration.close();
                registration = null;
            }
        }

        @Override
        public void onNetworkChunk() {
            checkpoint();
        }

        @Override
        public void onContentDelta(String text) {
            projector.accept(text);
        }

        String finish(String expectedText) {
            // Compared in the demasked domain, because that is the domain the batches were
            // streamed in and the domain the answer is persisted in. The equality is what proves
            // the member read exactly the answer that was stored — and it doubles as a check that
            // no placeholder was split across a batch, since a split one would demask differently
            // here than it did chunk by chunk.
            String projected = projector.finish();
            String comparable = demasking
                    ? Demasker.demask(projected, maskingContext).text()
                    : projected;
            if (!comparable.equals(expectedText)) {
                throw new AiAssistantLoopException("malformed_output", "malformed_output");
            }
            if (excluded || SpecialCareTextScreen.screen(expectedText).excluded()) {
                pending.setLength(0);
                return MaskingEngine.OMITTED_BY_POLICY;
            }
            flush();
            return expectedText;
        }

        void requireNoTerminalText() {
            if (projector.hasProjectedText()) {
                throw new AiAssistantLoopException("malformed_output", "malformed_output");
            }
        }

        boolean hasProjectedText() {
            return projector.hasProjectedText();
        }
    }
}
