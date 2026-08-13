package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;

import ooo.klae.connex.backend.ai.AiPrivacyMode;

/**
 * Organization-scoped AI provider configuration resolved for a single model call.
 * @param provider provider id
 * @param region nullable provider region or location
 * @param modelId provider model id
 * @param endpoint nullable provider endpoint
 * @param apiVersion nullable provider API version
 * @param deployment nullable provider deployment
 * @param projectId nullable provider project identifier
 * @param allowInternalEndpoint whether private endpoint addresses are allowed
 * @param imageInputSupported whether the exact resolved provider/model snapshot supports images
 * @param privacyMode current operator- and attestation-gated disclosure posture
 * @param credentials decrypted credential material for provider use
 */
public record ResolvedAiProvider(
        String provider,
        String region,
        String modelId,
        String endpoint,
        String apiVersion,
        String deployment,
        String projectId,
        boolean allowInternalEndpoint,
        boolean imageInputSupported,
        AiPrivacyMode privacyMode,
        AiCredentials credentials) {

    public ResolvedAiProvider {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(privacyMode, "privacyMode");
        Objects.requireNonNull(credentials, "credentials");
    }

    /** Creates the legacy masked provider snapshot. */
    public ResolvedAiProvider(
            String provider,
            String region,
            String modelId,
            String endpoint,
            String apiVersion,
            String deployment,
            String projectId,
            boolean allowInternalEndpoint,
            boolean imageInputSupported,
            AiCredentials credentials) {
        this(provider, region, modelId, endpoint, apiVersion, deployment, projectId,
                allowInternalEndpoint, imageInputSupported, AiPrivacyMode.MASKED, credentials);
    }

    /**
     * Converts this resolved provider into the provider adapter target.
     * @return provider target without credential material
     */
    public AiProviderTarget target() {
        return new AiProviderTarget(provider, region, modelId, endpoint, apiVersion, deployment,
                projectId, allowInternalEndpoint);
    }

    @Override
    public String toString() {
        return "ResolvedAiProvider[provider=" + provider
                + ", region=" + region
                + ", modelId=" + modelId
                + ", endpoint=" + endpoint
                + ", apiVersion=" + apiVersion
                + ", deployment=" + deployment
                + ", projectId=" + projectId
                + ", allowInternalEndpoint=" + allowInternalEndpoint
                + ", imageInputSupported=" + imageInputSupported
                + ", privacyMode=" + privacyMode
                + ", credentials=<redacted>]";
    }
}
