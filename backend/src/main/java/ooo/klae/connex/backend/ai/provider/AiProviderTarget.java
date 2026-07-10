package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;

/**
 * Configured provider location selected by the invocation layer. The provider adapter converts
 * this target into a fixed, allowlisted endpoint rather than trusting endpoint input.
 * @param provider provider id, currently {@code bedrock}
 * @param region provider region code
 * @param modelId provider model id
 */
public record AiProviderTarget(String provider, String region, String modelId) {

    public AiProviderTarget {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(modelId, "modelId");
    }
}
