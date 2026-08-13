package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;

/** Exact adapter capabilities for one configured provider target. */
public record AiProviderCapabilities(
        AiStructuredOutputEnforcement structuredOutput,
        AiReasoningMode reasoning,
        int contextWindowTokens) {
    private static final int ESTIMATED_UTF8_BYTES_PER_TOKEN = 4;

    public AiProviderCapabilities {
        Objects.requireNonNull(structuredOutput, "structuredOutput");
        Objects.requireNonNull(reasoning, "reasoning");
        if (contextWindowTokens < 4_096) {
            throw new IllegalArgumentException("AI context window must contain at least 4096 tokens");
        }
    }

    /**
     * Converts a token-denominated context window into the shared UTF-8 byte estimate.
     * @param contextTokens provider context-window tokens
     * @param outputTokens output tokens reserved from that context
     * @return estimated UTF-8 bytes available for serialized provider input
     */
    public static int estimatedInputByteCeiling(int contextTokens, int outputTokens) {
        int inputTokens = inputTokenBudget(contextTokens, outputTokens);
        return inputTokens > Integer.MAX_VALUE / ESTIMATED_UTF8_BYTES_PER_TOKEN
                ? Integer.MAX_VALUE
                : inputTokens * ESTIMATED_UTF8_BYTES_PER_TOKEN;
    }

    /**
     * Converts a token-denominated context window into a conservative UTF-8 admission ceiling.
     * @param contextTokens provider context-window tokens
     * @param outputTokens output tokens reserved from that context
     * @return maximum admitted UTF-8 bytes under the dense one-token-per-byte assumption
     */
    public static int conservativeInputByteCeiling(int contextTokens, int outputTokens) {
        return inputTokenBudget(contextTokens, outputTokens);
    }

    /**
     * Converts a UTF-8 byte reservation back into the shared token estimate.
     * @param utf8Bytes serialized UTF-8 bytes to reserve
     * @return estimated tokens required for the byte reservation
     */
    public static int estimatedTokensForBytes(int utf8Bytes) {
        if (utf8Bytes < 0) {
            throw new IllegalArgumentException("AI input byte budget must be non-negative");
        }
        return utf8Bytes / ESTIMATED_UTF8_BYTES_PER_TOKEN
                + (utf8Bytes % ESTIMATED_UTF8_BYTES_PER_TOKEN == 0 ? 0 : 1);
    }

    private static int inputTokenBudget(int contextTokens, int outputTokens) {
        if (contextTokens < 1 || outputTokens < 0) {
            throw new IllegalArgumentException("AI context and output budgets must be non-negative");
        }
        return Math.max(
                1,
                contextTokens - Math.min(contextTokens - 1, outputTokens));
    }
}
