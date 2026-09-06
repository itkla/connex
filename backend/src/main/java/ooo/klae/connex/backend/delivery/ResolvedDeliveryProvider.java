package ooo.klae.connex.backend.delivery;

import java.util.Objects;

import ooo.klae.connex.backend.mail.ResolvedMailConfig;

/**
 * Workspace-scoped delivery provider configuration resolved for one send or one inbound webhook.
 * Carries only the non-secret settings a dispatcher or event source needs plus the decrypted
 * credential bundle. SMTP targets also carry the exact resolved mail configuration so the
 * provider cannot silently switch accounts between claim and egress.
 * @param providerId the installed provider id
 * @param channel the delivery channel
 * @param workspaceId the workspace whose transport governs the send
 * @param endpoint the provider send endpoint, or null when the provider carries none
 * @param fromAddress the envelope from-address, or null when the provider carries none
 * @param fromName the from display name, or null when none
 * @param credentials decrypted credential material for provider use
 * @param idempotentSubmission whether this exact connector guarantees deduplication of repeated keys
 * @param attemptTargetFingerprint non-secret identity of the exact attempted configuration
 * @param mailConfig exact SMTP configuration for this attempt, or null for non-SMTP providers
 */
public record ResolvedDeliveryProvider(
        String providerId,
        DeliveryChannel channel,
        int workspaceId,
        String endpoint,
        String fromAddress,
        String fromName,
        DeliveryCredentials credentials,
        boolean idempotentSubmission,
        String attemptTargetFingerprint,
        ResolvedMailConfig mailConfig) {

    public ResolvedDeliveryProvider {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(attemptTargetFingerprint, "attemptTargetFingerprint");
    }

    public ResolvedDeliveryProvider(
            String providerId,
            DeliveryChannel channel,
            int workspaceId,
            String endpoint,
            String fromAddress,
            String fromName,
            DeliveryCredentials credentials) {
        this(
                providerId,
                channel,
                workspaceId,
                endpoint,
                fromAddress,
                fromName,
                credentials,
                false,
                DeliveryTargetFingerprint.create(
                        providerId,
                        "unversioned",
                        endpoint == null ? "no-endpoint" : endpoint,
                        providerId + ":" + workspaceId),
                null);
    }

    /**
     * Builds a resolved provider with a deterministic opaque configuration identity.
     * @param providerId the installed provider id
     * @param channel the delivery channel
     * @param workspaceId the workspace
     * @param credentials decrypted credential material
     * @return the resolved provider with null endpoint/from settings
     */
    public static ResolvedDeliveryProvider of(
            String providerId, DeliveryChannel channel, int workspaceId, DeliveryCredentials credentials) {
        return new ResolvedDeliveryProvider(providerId, channel, workspaceId, null, null, null, credentials);
    }

    /**
     * Builds a resolved SMTP target carrying the exact transport and its non-secret attempt identity.
     *
     * @param providerId installed provider id
     * @param channel delivery channel
     * @param workspaceId owning workspace
     * @param credentials provider credentials
     * @param attemptTargetFingerprint exact non-secret attempt identity
     * @param mailConfig exact resolved SMTP transport
     * @return resolved SMTP target
     */
    public static ResolvedDeliveryProvider of(
            String providerId,
            DeliveryChannel channel,
            int workspaceId,
            DeliveryCredentials credentials,
            String attemptTargetFingerprint,
            ResolvedMailConfig mailConfig) {
        return new ResolvedDeliveryProvider(
                providerId,
                channel,
                workspaceId,
                null,
                null,
                null,
                credentials,
                false,
                attemptTargetFingerprint,
                Objects.requireNonNull(mailConfig, "mailConfig"));
    }

    @Override
    public String toString() {
        return "ResolvedDeliveryProvider[providerId=" + providerId
                + ", channel=" + channel
                + ", workspaceId=" + workspaceId
                + ", endpoint=" + endpoint
                + ", credentials=<redacted>]";
    }
}
