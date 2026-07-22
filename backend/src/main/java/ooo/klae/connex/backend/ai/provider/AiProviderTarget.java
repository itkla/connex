package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;

/**
 * Configured provider location selected by the invocation layer.
 * @param provider provider id
 * @param region nullable provider region or location
 * @param modelId provider model id
 * @param endpoint nullable provider endpoint
 * @param apiVersion nullable provider API version
 * @param deployment nullable provider deployment
 * @param projectId nullable provider project identifier
 * @param allowInternalEndpoint whether private endpoint addresses are allowed
 */
public record AiProviderTarget(
        String provider,
        String region,
        String modelId,
        String endpoint,
        String apiVersion,
        String deployment,
        String projectId,
        boolean allowInternalEndpoint) {

    public AiProviderTarget {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(modelId, "modelId");
    }
}
