package ooo.klae.connex.backend.ai;

import java.util.Objects;

/**
 * Credential-free generation inputs that determine AI output-cache validity.
 * @param provider provider id
 * @param region nullable provider region or location
 * @param modelId provider model id
 * @param endpoint nullable provider endpoint
 * @param deployment nullable provider deployment
 * @param apiVersion nullable provider API version
 * @param projectId nullable provider project identifier
 * @param maxTokens provider output token cap
 * @param temperature provider sampling temperature
 */
public record AiGenerationProfile(
        String provider,
        String region,
        String modelId,
        String endpoint,
        String deployment,
        String apiVersion,
        String projectId,
        int maxTokens,
        double temperature) {

    public AiGenerationProfile {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(modelId, "modelId");
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("AI generation max tokens must be positive");
        }
        if (!Double.isFinite(temperature) || temperature < 0) {
            throw new IllegalArgumentException("AI generation temperature must be finite and non-negative");
        }
    }

    @Override
    public String toString() {
        return "AiGenerationProfile[provider=" + provider
                + ", region=" + region
                + ", modelId=" + modelId
                + ", endpoint=<redacted>"
                + ", deployment=" + deployment
                + ", apiVersion=" + apiVersion
                + ", projectId=" + projectId
                + ", maxTokens=" + maxTokens
                + ", temperature=" + temperature
                + ']';
    }
}
