package ooo.klae.connex.backend.ai;

/**
 * Implemented by the BYOP provider-settings service in a later PR. Returns true
 * only when the organization has an enabled, fully-configured provider with
 * credentials, region, and no-training attestation. When no bean implements this
 * interface, the gate treats readiness as false so AI features fail closed.
 */
public interface AiProviderReadiness {
    boolean isReadyForOrg(int orgId);
}
