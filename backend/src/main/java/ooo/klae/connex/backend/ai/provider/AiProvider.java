package ooo.klae.connex.backend.ai.provider;

/**
 * Narrow outbound AI provider seam consumed by feature invocation code.
 */
public interface AiProvider {
    String providerId();
    AiStructuredOutputEnforcement structuredOutputCapability(AiProviderTarget target);
    AiCompletionResult complete(AiCompletionRequest request);
}
