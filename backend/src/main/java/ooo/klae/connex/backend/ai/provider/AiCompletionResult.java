package ooo.klae.connex.backend.ai.provider;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Normalized provider completion response returned to the invocation layer. The provider-supplied
 * stop reason is mapped to a closed internal vocabulary so an untrusted provider cannot smuggle
 * arbitrary text (for example a leaked credential) into audit metadata or logs.
 * @param text generated text
 * @param inputTokens provider-reported input token count
 * @param outputTokens provider-reported output token count
 * @param stopReason normalized stop reason drawn from a closed vocabulary
 * @param structuredOutputEnforcement provider-native structured enforcement actually applied
 */
public record AiCompletionResult(
        String text,
        int inputTokens,
        int outputTokens,
        String stopReason,
        AiStructuredOutputEnforcement structuredOutputEnforcement) {

    private static final String STOP_REASON_OTHER = "other";
    private static final Set<String> KNOWN_STOP_REASONS = Set.of(
            "stop",
            "length",
            "content_filter",
            "tool_calls",
            "function_call",
            "end_turn",
            "max_tokens",
            "stop_sequence",
            "tool_use",
            "pause_turn",
            "refusal",
            "safety",
            "recitation",
            "blocklist",
            "prohibited_content",
            "spii",
            "malformed_function_call",
            "language",
            STOP_REASON_OTHER);

    public AiCompletionResult {
        text = Objects.requireNonNull(text, "text");
        stopReason = normalizeStopReason(stopReason);
        Objects.requireNonNull(structuredOutputEnforcement, "structuredOutputEnforcement");
    }

    public AiCompletionResult(String text, int inputTokens, int outputTokens, String stopReason) {
        this(text, inputTokens, outputTokens, stopReason,
                AiStructuredOutputEnforcement.PROMPT_ONLY);
    }

    private static String normalizeStopReason(String stopReason) {
        if (stopReason == null) {
            return STOP_REASON_OTHER;
        }
        String normalized = stopReason.trim().toLowerCase(Locale.ROOT);
        return KNOWN_STOP_REASONS.contains(normalized) ? normalized : STOP_REASON_OTHER;
    }

    @Override
    public String toString() {
        return "AiCompletionResult[text=<redacted>, inputTokens=" + inputTokens
                + ", outputTokens=" + outputTokens + ", stopReason=" + stopReason
                + ", structuredOutputEnforcement=" + structuredOutputEnforcement + "]";
    }
}
