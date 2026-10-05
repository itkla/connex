package ooo.klae.connex.backend.ai.assistant;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ooo.klae.connex.backend.ai.provider.AiProviderCapabilities;

/**
 * The durable idempotency key one assistant tool call owns, and the only place its grammar lives.
 *
 * <p>Every {@code ai_chat_tool_call} row the assistant writes is keyed
 * {@code turn-{turnId}-step-{stepNumber}} when it was the sole call of its step, and
 * {@code turn-{turnId}-step-{stepNumber}-call-{callOrdinal}} when it shared its step with other
 * calls. The row's turn and step are recovered by parsing that key back, so the producer, the
 * turn-prefix scan and every reader must agree on one grammar; keeping the rendering, the prefix
 * and the parser here is what makes a divergent copy impossible to write by accident.
 *
 * <p>The sole call of a step renders no suffix at all, so every executed or proposed write, every
 * executed {@code find_tools}, every unbatched read and every server-side skill plan step keeps the
 * exact key it has always had. A call that shared its step renders {@code -call-k}, which fits the
 * existing column and its uniqueness constraint. That includes the failed row of every call of a
 * batch refused whole before anything ran, a write or {@code find_tools} among them; such a row is
 * never executed, never a pending proposal and never the loaded toolset's source.
 *
 * <p>Parsing is anchored and strict. The turn, step and ordinal must be positive decimal numbers
 * without leading zeros, the step is bounded by the loop's own backstop and the ordinal by the
 * per-step call ceiling, and a number too large to read is refused rather than truncated. A key
 * claiming a position no step could have produced therefore names no call, and a malformed key is
 * rejected exactly as it would be had it never parsed at all.
 *
 * @param turnId the durable turn id, from 1
 * @param stepNumber the durable model-step number, from 1 to the loop's hard step ceiling
 * @param callOrdinal the call's position in its step, or
 *     {@link AiAssistantToolCallRef#SOLE_CALL} when it is the step's only call
 */
public record AiAssistantToolCallKey(int turnId, int stepNumber, int callOrdinal) {
    private static final String TURN = "turn-";
    private static final String STEP = "-step-";
    private static final String CALL = "-call-";
    private static final Pattern KEY = Pattern.compile(
            "^turn-([1-9][0-9]*)-step-([1-9][0-9]*)(?:-call-([1-9][0-9]*))?$");

    public AiAssistantToolCallKey {
        if (turnId <= 0
                || stepNumber <= 0
                || stepNumber > AiChatAgentLoopService.HARD_MAX_STEPS) {
            throw new IllegalArgumentException("Assistant tool turn and step must be positive");
        }
        if (callOrdinal < 0 || callOrdinal > AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS) {
            throw new IllegalArgumentException(
                    "Assistant tool call ordinal must be between 0 and "
                            + AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS);
        }
    }

    /**
     * Reads a stored idempotency key back into the call it names.
     *
     * @param idempotencyKey the stored key, possibly null or malformed
     * @return the call the key names, or empty when the key is not one this grammar produces
     */
    public static Optional<AiAssistantToolCallKey> parse(String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        Matcher matcher = KEY.matcher(idempotencyKey);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            String callOrdinal = matcher.group(3);
            return Optional.of(new AiAssistantToolCallKey(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    callOrdinal == null
                            ? AiAssistantToolCallRef.SOLE_CALL
                            : Integer.parseInt(callOrdinal)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * Renders the prefix every key of one turn starts with, for the turn-scoped row scan.
     *
     * @param turnId the durable turn id
     * @return the {@code turn-N-step-} prefix
     */
    public static String turnPrefix(int turnId) {
        return TURN + turnId + STEP;
    }

    /**
     * Renders the durable idempotency key this call owns.
     *
     * @return {@code turn-N-step-M} for the sole call of a step, {@code turn-N-step-M-call-k}
     *     otherwise
     */
    public String value() {
        return turnPrefix(turnId) + stepNumber
                + (callOrdinal == AiAssistantToolCallRef.SOLE_CALL ? "" : CALL + callOrdinal);
    }
}
