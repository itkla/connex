package ooo.klae.connex.backend.ai.assistant;

import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.masking.SpecialCareTextScreen;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;

/** Batches decoded terminal text into durable UTF-16-sequenced realtime frames. */
final class AiChatStreamingProgress {
    private static final int BATCH_CHARACTERS = 256;
    private static final long CHECK_NANOS = java.time.Duration.ofMillis(250).toNanos();

    private final AiChatQueuedTurn turn;
    private final AiChatTurnPersistenceService persistenceService;
    private final StringBuilder durable = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private long lastCheckNanos = System.nanoTime();
    private boolean excluded;

    AiChatStreamingProgress(
            AiChatQueuedTurn turn,
            AiChatTurnPersistenceService persistenceService) {
        this.turn = java.util.Objects.requireNonNull(turn, "turn");
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
        pending.append(text);
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

    final class Observer implements AiProviderStreamObserver {
        private final AiAssistantTextDeltaProjector projector;
        private AiChatCancellationHooks.Registration registration;

        private Observer(AiAssistantTextDeltaProjector.Shape shape) {
            projector = new AiAssistantTextDeltaProjector(shape, AiChatStreamingProgress.this::acceptDecoded);
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
            String projected = projector.finish();
            if (!projected.equals(expectedText)) {
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
