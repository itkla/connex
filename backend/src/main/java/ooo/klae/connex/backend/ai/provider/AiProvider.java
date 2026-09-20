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

    /** @return conservative configured-target maximum generated output in tokens */
    default int maxOutputTokens(AiProviderTarget target) {
        return 4_096;
    }

    /** @return provider function-tool protocol for the configured target */
    default AiToolCallingMode toolCallingCapability(AiProviderTarget target) {
        return AiToolCallingMode.NONE;
    }

    /** @return whether the configured target supports normalized completion streaming */
    default boolean supportsStreaming(AiProviderTarget target) {
        return false;
    }

    /**
     * How many function calls one model step of the configured target may carry.
     *
     * <p>One unless an adapter says otherwise, so an adapter that later gains native function tools
     * stays single-call until it has been probed and declared rather than by the time someone
     * notices. The value rides on the request the loop builds, so the adapter that serializes the
     * call and the parser that bounds the response read one source of truth.
     *
     * @param target configured provider target
     * @return the per-step call ceiling, at least 1 and never above
     *     {@link AiProviderCapabilities#MAX_PARALLEL_TOOL_CALLS}
     */
    default int parallelToolCallLimit(AiProviderTarget target) {
        return 1;
    }

    AiCompletionResult complete(AiCompletionRequest request);

    /** Completes one normalized provider stream while preserving the full buffered result. */
    default AiCompletionResult completeStreaming(
            AiCompletionRequest request,
            AiProviderStreamObserver observer) {
        throw new AiProviderException("AI provider does not support streaming");
    }
}
