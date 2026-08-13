package ooo.klae.connex.backend.ai;

import java.util.Optional;

/**
 * Reports readiness and returns generation profiles only when the organization has an enabled,
 * fully-configured provider with credentials, region, and no-training attestation. The gate treats
 * missing readiness as false.
 */
public interface AiProviderReadiness {
    boolean isReadyForOrg(int orgId);

    boolean isImageInputReadyForOrg(int orgId);

    Optional<AiGenerationProfile> generationProfileForOrg(
            int orgId, int maxTokens, double temperature);

    /** Returns the current fail-closed provider disclosure mode for an organization. */
    default AiPrivacyMode privacyModeForOrg(int orgId) {
        return AiPrivacyMode.MASKED;
    }
}
