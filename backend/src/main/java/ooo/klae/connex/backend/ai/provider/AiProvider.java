package ooo.klae.connex.backend.ai.provider;

/**
 * Narrow outbound AI provider seam consumed by feature invocation code. Implementations own
 * provider endpoint construction internally and must never accept a caller-supplied endpoint.
 */
public interface AiProvider {
    AiCompletionResult complete(AiCompletionRequest request);
}
