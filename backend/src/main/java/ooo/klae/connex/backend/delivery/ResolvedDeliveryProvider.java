package ooo.klae.connex.backend.delivery;

import java.util.Objects;

/**
 * Workspace-scoped delivery provider configuration resolved for one send. Carries only the
 * non-secret settings a dispatcher needs plus the decrypted credential bundle; the SMTP provider
 * re-resolves the workspace mail transport from {@code workspaceId}.
 * @param providerId the installed provider id
 * @param channel the delivery channel
 * @param workspaceId the workspace whose transport governs the send
 * @param credentials decrypted credential material for provider use
 */
public record ResolvedDeliveryProvider(
        String providerId,
        DeliveryChannel channel,
        int workspaceId,
        DeliveryCredentials credentials) {

    public ResolvedDeliveryProvider {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(credentials, "credentials");
    }

    @Override
    public String toString() {
        return "ResolvedDeliveryProvider[providerId=" + providerId
                + ", channel=" + channel
                + ", workspaceId=" + workspaceId
                + ", credentials=<redacted>]";
    }
}
