package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;

/**
 * Organization-scoped AI provider configuration resolved for a single model call.
 * @param provider provider id
 * @param region provider region code
 * @param modelId provider model id
 * @param credentials decrypted credential material for provider use
 */
public record ResolvedAiProvider(String provider, String region, String modelId, AiCredentials credentials) {

    public ResolvedAiProvider {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(credentials, "credentials");
    }

    /**
     * Converts this resolved provider into the provider adapter target.
     * @return provider target without credential material
     */
    public AiProviderTarget target() {
        return new AiProviderTarget(provider, region, modelId);
    }

    @Override
    public String toString() {
        return "ResolvedAiProvider[provider=" + provider
                + ", region=" + region
                + ", modelId=" + modelId
                + ", credentials=<redacted>]";
    }
}
