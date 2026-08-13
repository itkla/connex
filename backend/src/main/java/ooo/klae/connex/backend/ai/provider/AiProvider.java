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

    /** @return reasoning protocol used specifically alongside native function tools */
    default AiReasoningMode nativeToolReasoningCapability(AiProviderTarget target) {
        return reasoningCapability(target);
    }

    /** @return conservative configured-target context-window size in tokens */
    default int contextWindowTokens(AiProviderTarget target) {
        return 4_096;
    }

    /** @return provider function-tool protocol for the configured target */
    default AiToolCallingMode toolCallingCapability(AiProviderTarget target) {
        return AiToolCallingMode.NONE;
    }

    AiCompletionResult complete(AiCompletionRequest request);
}
