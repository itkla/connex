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
    /** The durable partial-content bound this batcher must never hand to persistence. */
    private static final int MAX_STREAM_CHARACTERS = 16_000;
    private static final long CHECK_NANOS = java.time.Duration.ofMillis(250).toNanos();

    private final AiChatQueuedTurn turn;
    private final AiChatTurnPersistenceService persistenceService;
    private final MaskingContext maskingContext;
    private final boolean demasking;
    private final StringBuilder durable = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private long lastCheckNanos = System.nanoTime();
    private boolean excluded;
    private boolean streamTruncated;

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

    /**
     * Demasks, bounds, screens, and stages one projected answer fragment.
     *
     * <p>Blank text passes through undemasked: it can hold no placeholder, and the demasker
     * answers a blank input with the empty string — which would swallow the space or newline a
     * provider commonly delivers as its own delta and run the words together.
     *
     * <p>Demasking expands, so an answer that fits the durable bound while masked can exceed it
     * once names replace placeholders. Reaching the bound stops the stream rather than appending:
     * the settled answer still carries the whole text, whereas throwing here — inside a provider
     * callback — would end the turn as a provider error over a display concern.
     *
     * <p>Screening runs on the demasked text for a masked turn, which is the text the member
     * actually sees. Screening the masked form would be close to vacuous: every value that could
     * carry special-care content has already become a placeholder by then.
     */
    private void acceptDecoded(String text) {
        if (excluded || streamTruncated) {
            return;
        }
        String decoded = demasking && !text.isBlank()
                ? Demasker.demask(text, maskingContext).text()
                : text;
        if (durable.length() + pending.length() + decoded.length() > MAX_STREAM_CHARACTERS) {
            streamTruncated = true;
            return;
        }
        pending.append(decoded);
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

    /**
     * Forgets one attempt's stream so the repaired attempt streams from nothing.
     *
     * <p>Everything the attempt established is per-attempt state — the retained batches, the
     * staged text, a screening exclusion, and a stream stopped at the durable bound. A truncation
     * left standing here would silently swallow the whole repaired attempt: every delta would hit
     * the truncated early-return, and settling would skip the emitted-stream comparison that
     * exists to catch exactly that disagreement.
     */
    void reset() {
        persistenceService.resetPartialContent(turn, durable.length());
        durable.setLength(0);
        pending.setLength(0);
        excluded = false;
        streamTruncated = false;
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

        /**
         * Settles the stream against the answer the turn is about to persist.
         *
         * <p>Both comparisons run in the demasked domain, because that is the domain the batches
         * were streamed in and the domain the answer is persisted in. The first compares the whole
         * projection; the second compares the stream the member actually read, batch by batch, so
         * a placeholder that demasked differently in pieces than as a whole is caught here rather
         * than silently leaving the transcript disagreeing with the screen. A stream stopped at
         * the durable bound is a known prefix, not a mismatch.
         */
        String finish(String expectedText) {
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
            if (!streamTruncated && !(durable.toString() + pending).equals(expectedText)) {
                throw new AiAssistantLoopException("malformed_output", "malformed_output");
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
