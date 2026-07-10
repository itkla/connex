package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;

/**
 * Normalized provider completion response returned to the invocation layer.
 * @param text generated text
 * @param inputTokens provider-reported input token count
 * @param outputTokens provider-reported output token count
 * @param stopReason provider stop reason
 */
public record AiCompletionResult(String text, int inputTokens, int outputTokens, String stopReason) {

    public AiCompletionResult {
        Objects.requireNonNull(text, "text");
    }
}
