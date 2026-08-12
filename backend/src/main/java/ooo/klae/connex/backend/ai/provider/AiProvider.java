package ooo.klae.connex.backend.ai.provider;

/**
 * Narrow outbound AI provider seam consumed by feature invocation code.
 */
public interface AiProvider {
    String providerId();
    AiStructuredOutputEnforcement structuredOutputCapability(AiProviderTarget target);

    /** @return provider reasoning protocol for the configured target */
    default AiReasoningMode reasoningCapability(AiProviderTarget target) {
        return AiReasoningMode.TAGGED;
    }

    /** @return conservative configured-target context-window size in tokens */
    default int contextWindowTokens(AiProviderTarget target) {
        return 4_096;
    }

    AiCompletionResult complete(AiCompletionRequest request);
}
