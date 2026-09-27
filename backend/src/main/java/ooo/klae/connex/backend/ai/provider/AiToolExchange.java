package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;

/**
 * One ephemeral assistant function call followed by its masked tool-role result.
 *
 * <p>The exchange also names which assistant message it belonged to. An adapter replays one
 * assistant message per run of exchanges sharing a {@code step}, so the cardinality of a replayed
 * message's {@code tool_calls} equals what the model emitted for that step rather than whatever
 * survived the turn's budget.
 *
 * @param call the assistant's own function call, replayed verbatim
 * @param maskedResult the masked tool-role result that answered it
 * @param step the model step this call belonged to, from 1
 * @param callOrdinal the call's position within that step, or 0 when it was the step's only call
 */
public record AiToolExchange(AiToolCall call, String maskedResult, int step, int callOrdinal) {
    public AiToolExchange {
        Objects.requireNonNull(call, "call");
        if (maskedResult == null || maskedResult.isBlank()) {
            throw new IllegalArgumentException("AI tool result is required");
        }
        if (step <= 0) {
            throw new IllegalArgumentException("AI tool exchange step must be positive");
        }
        if (callOrdinal < 0) {
            throw new IllegalArgumentException("AI tool exchange call ordinal must not be negative");
        }
    }

    @Override
    public String toString() {
        return "AiToolExchange[call=" + call + ", maskedResult=<redacted>"
                + ", step=" + step + ", callOrdinal=" + callOrdinal + "]";
    }
}
