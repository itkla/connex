package ooo.klae.connex.backend.ai;

/**
 * Returns true only when the organization has an enabled, fully-configured provider with
 * credentials, region, and no-training attestation. The gate treats missing readiness as false.
 */
public interface AiProviderReadiness {
    boolean isReadyForOrg(int orgId);
}
