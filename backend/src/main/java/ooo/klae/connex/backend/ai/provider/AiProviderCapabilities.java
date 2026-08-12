package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;

/** Exact adapter capabilities for one configured provider target. */
public record AiProviderCapabilities(
        AiStructuredOutputEnforcement structuredOutput,
        AiReasoningMode reasoning,
        int contextWindowTokens) {

    public AiProviderCapabilities {
        Objects.requireNonNull(structuredOutput, "structuredOutput");
        Objects.requireNonNull(reasoning, "reasoning");
        if (contextWindowTokens < 4_096) {
            throw new IllegalArgumentException("AI context window must contain at least 4096 tokens");
        }
    }
}
