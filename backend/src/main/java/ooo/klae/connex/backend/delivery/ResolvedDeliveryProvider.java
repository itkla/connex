package ooo.klae.connex.backend.delivery;

import java.util.Objects;

/**
 * Workspace-scoped delivery provider configuration resolved for one send or one inbound webhook.
 * Carries only the non-secret settings a dispatcher or event source needs plus the decrypted
 * credential bundle; the SMTP provider re-resolves the workspace mail transport from
 * {@code workspaceId} and leaves the endpoint/from settings null.
 * @param providerId the installed provider id
 * @param channel the delivery channel
 * @param workspaceId the workspace whose transport governs the send
 * @param endpoint the provider send endpoint, or null when the provider carries none
 * @param fromAddress the envelope from-address, or null when the provider carries none
 * @param fromName the from display name, or null when none
 * @param credentials decrypted credential material for provider use
 */
public record ResolvedDeliveryProvider(
        String providerId,
        DeliveryChannel channel,
        int workspaceId,
        String endpoint,
        String fromAddress,
        String fromName,
        DeliveryCredentials credentials) {

    public ResolvedDeliveryProvider {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(credentials, "credentials");
    }

    /**
     * Builds a resolved provider that carries no endpoint/from settings of its own — the SMTP seam,
     * where the dispatcher re-resolves the workspace mail transport.
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

    @Override
    public String toString() {
        return "ResolvedDeliveryProvider[providerId=" + providerId
                + ", channel=" + channel
                + ", workspaceId=" + workspaceId
                + ", endpoint=" + endpoint
                + ", credentials=<redacted>]";
    }
}
