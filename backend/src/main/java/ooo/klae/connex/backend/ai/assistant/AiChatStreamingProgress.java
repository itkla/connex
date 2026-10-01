package ooo.klae.connex.backend.ai.assistant;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.masking.Demasker;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.masking.SpecialCareTextScreen;
import ooo.klae.connex.backend.ai.provider.AiProviderStreamObserver;
import ooo.klae.connex.backend.ai.provider.AiReasoningMode;

/** Batches decoded terminal text into durable UTF-16-sequenced realtime frames. */
final class AiChatStreamingProgress {
    private static final int BATCH_CHARACTERS = 256;
    private static final Pattern TASK_HANDLE_SUFFIX = Pattern.compile(
            "t(?:[1-9][0-9]*)?\\z");
    private static final Pattern SOURCE_GRAPHEME = Pattern.compile("\\X");
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
    private boolean taskHandle;

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
        if (excluded || streamTruncated || taskHandle) {
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
        String accumulated = durable.toString() + pending;
        String settled = accumulated.substring(0, stablePrefixLength(accumulated));
        if (AiAssistantStepGuard.containsTaskHandle(settled)) {
            persistenceService.resetPartialContent(turn, durable.length());
            durable.setLength(0);
            pending.setLength(0);
            taskHandle = true;
            return;
        }
        if (SpecialCareTextScreen.screen(durable.toString() + pending).excluded()) {
            excluded = true;
            pending.setLength(0);
            return;
        }
        if (pending.length() >= BATCH_CHARACTERS) {
            flush();
        }
    }

    /**
     * Holds only suffixes whose raw or canonical task-handle status can still change. A final
     * label close awaits lookahead; a closed label followed by anything but an opening parenthesis
     * is settled. Source boundaries are checked again because withholding a link can expose a
     * preceding task prefix. Only finish may release such a suffix without further lookahead.
     */
    private int stablePrefixLength(String text) {
        int boundary = text.length();
        boolean retainContext = true;
        while (boundary > durable.length()) {
            String prefix = text.substring(0, boundary);
            String prepared = MaskingEngine.prepareConversationalText(prefix);
            int preparedBoundary = unresolvedLinkStart(prepared);
            preparedBoundary = taskHandleSuffixStart(prepared.substring(0, preparedBoundary), retainContext);
            int nextBoundary = taskHandleSuffixStart(prefix, retainContext);
            if (preparedBoundary < prepared.length()) {
                nextBoundary = Math.min(nextBoundary,
                        sourceBoundary(prefix, prepared.substring(0, preparedBoundary),
                                prepared.codePointAt(preparedBoundary)));
            }
            if (nextBoundary == boundary) {
                return boundary;
            }
            boundary = nextBoundary;
            retainContext = false;
        }
        return durable.length();
    }

    /** Maps a prepared boundary back before any source link enclosing the withheld suffix. */
    private int sourceBoundary(String text, String stablePrepared, int withheldCodePoint) {
        int[] candidates = new int[text.length()];
        int count = 0;
        Matcher graphemes = SOURCE_GRAPHEME.matcher(text);
        while (graphemes.find()) {
            String unit = Normalizer.normalize(graphemes.group(), Normalizer.Form.NFKC);
            if (graphemes.start() >= durable.length()
                    && (unit.indexOf('[') >= 0 || unit.indexOf(withheldCodePoint) >= 0)) {
                candidates[count++] = graphemes.start();
            }
        }
        while (count > 0) {
            int offset = candidates[--count];
            String prefix = MaskingEngine.prepareConversationalText(text.substring(0, offset));
            if (stablePrepared.startsWith(prefix) && unresolvedLinkStart(prefix) == prefix.length()) {
                return offset;
            }
        }
        return durable.length();
    }

    /** Retains one word character as left context for a handle that could start the next batch. */
    private static int taskHandleSuffixStart(String text, boolean retainContext) {
        int boundary = text.length();
        Matcher suffix = TASK_HANDLE_SUFFIX.matcher(text);
        if (!suffix.find()) {
            if (retainContext && boundary > 0) {
                int last = text.codePointBefore(boundary);
                if (isHandleWordCharacter(last) || last == '{' || last == '}') {
                    boundary -= Character.charCount(last);
                }
            }
            suffix = TASK_HANDLE_SUFFIX.matcher(text.substring(0, boundary));
            if (!suffix.find()) {
                return boundary;
            }
        }
        boundary = suffix.start();
        if (boundary > 0 && isHandleWordCharacter(text.codePointBefore(boundary))) {
            if (!retainContext) {
                return text.length();
            }
            boundary = text.offsetByCodePoints(boundary, -1);
        }
        return boundary;
    }

    private static boolean isHandleWordCharacter(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isLetter(codePoint) || type == Character.DECIMAL_DIGIT_NUMBER
                || type == Character.LETTER_NUMBER || type == Character.OTHER_NUMBER || codePoint == '_';
    }

    private static int unresolvedLinkStart(String text) {
        int labelStart = -1;
        for (int offset = 0; offset < text.length(); offset++) {
            char value = text.charAt(offset);
            if (value == '[' && labelStart < 0) {
                labelStart = offset;
            } else if (value == ']' && labelStart >= 0) {
                if (offset + 1 == text.length()
                        || text.charAt(offset + 1) == '(' && text.indexOf(')', offset + 2) < 0) {
                    return labelStart;
                }
                labelStart = -1;
            }
        }
        return labelStart < 0 ? text.length() : labelStart;
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
        flush(false);
    }

    private void flush(boolean terminal) {
        if (pending.isEmpty()) {
            return;
        }
        int length = terminal ? pending.length()
                : stablePrefixLength(durable.toString() + pending) - durable.length();
        if (length == 0) {
            persistenceService.requireRunning(turn);
            return;
        }
        String batch = pending.substring(0, length);
        int nextOffset = persistenceService.appendPartialBatch(
                turn, durable.length(), batch);
        durable.append(batch);
        pending.delete(0, length);
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
        taskHandle = false;
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
            if (taskHandle || AiAssistantStepGuard.containsTaskHandle(expectedText)
                    || !comparable.equals(expectedText)) {
                throw new AiAssistantLoopException("malformed_output", "malformed_output");
            }
            if (excluded || SpecialCareTextScreen.screen(expectedText).excluded()) {
                pending.setLength(0);
                return MaskingEngine.OMITTED_BY_POLICY;
            }
            if (!streamTruncated && !(durable.toString() + pending).equals(expectedText)) {
                throw new AiAssistantLoopException("malformed_output", "malformed_output");
            }
            flush(!streamTruncated);
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
