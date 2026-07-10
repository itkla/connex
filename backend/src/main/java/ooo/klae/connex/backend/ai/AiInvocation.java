package ooo.klae.connex.backend.ai;

import java.util.Objects;

import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.ai.masking.MaskingContext;

/**
 * Request to the AI invocation choke point. The prompt and context are redacted from
 * {@link #toString()} because they may contain masked provider payloads or request-local
 * re-identification state.
 * @param feature stable feature identifier
 * @param context request-local masking context
 * @param prompt masked prompt to send
 * @param maxTokens provider output token cap
 * @param temperature provider sampling temperature
 */
public record AiInvocation(
        String feature,
        MaskingContext context,
        MaskedPrompt prompt,
        int maxTokens,
        double temperature) {

    public AiInvocation {
        Objects.requireNonNull(feature, "feature");
        if (feature.isBlank()) {
            throw new IllegalArgumentException("feature is required");
        }
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(prompt, "prompt");
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (!Double.isFinite(temperature) || temperature < 0) {
            throw new IllegalArgumentException("temperature must be a finite non-negative number");
        }
    }

    @Override
    public String toString() {
        return "AiInvocation[feature=" + feature
                + ", context=<redacted>"
                + ", prompt=<redacted>"
                + ", maxTokens=" + maxTokens
                + ", temperature=" + temperature + "]";
    }
}
