package ooo.klae.connex.backend.ai.provider;

import java.util.List;
import java.util.Objects;

import ooo.klae.connex.backend.ai.masking.OutboundSegment;

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
 * @param resultSegments request-local origin information covering exactly the masked result
 */
public record AiToolExchange(AiToolCall call, String maskedResult, int step, int callOrdinal,
        @com.fasterxml.jackson.annotation.JsonIgnore List<OutboundSegment> resultSegments) {
    /** Direct server JSON results carry static keys and tenant-bearing scalar values. */
    public AiToolExchange(AiToolCall call, String maskedResult, int step, int callOrdinal) {
        this(call, maskedResult, step, callOrdinal,
                List.of(new OutboundSegment(maskedResult, OutboundSegment.Origin.TENANT_TOOL_RESULT)));
    }

    public AiToolExchange {
        Objects.requireNonNull(call, "call");
        resultSegments = List.copyOf(resultSegments);
        if (!resultSegments.stream().map(OutboundSegment::text).collect(java.util.stream.Collectors.joining())
                .equals(maskedResult)) {
            throw new IllegalArgumentException("Native tool result provenance must cover its exact text");
        }
        if (maskedResult == null || maskedResult.isBlank()) {
            throw new IllegalArgumentException("AI tool result is required");
        }
        if (step <= 0) {
            throw new IllegalArgumentException("AI tool exchange step must be positive");
        }
        if (callOrdinal < 0 || callOrdinal > AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS) {
            throw new IllegalArgumentException(
                    "AI tool exchange call ordinal must be between 0 and "
                            + AiProviderCapabilities.MAX_PARALLEL_TOOL_CALLS);
        }
    }

    @Override
    public String toString() {
        return "AiToolExchange[call=" + call + ", maskedResult=<redacted>"
                + ", step=" + step + ", callOrdinal=" + callOrdinal + "]";
    }
}
