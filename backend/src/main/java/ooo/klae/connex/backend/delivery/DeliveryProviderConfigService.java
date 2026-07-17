package ooo.klae.connex.backend.delivery;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.delivery.provider.smtp.SmtpDeliveryProvider;
import ooo.klae.connex.backend.mail.MailConfigResolver;
import ooo.klae.connex.backend.mail.ResolvedMailConfig;

/**
 * Resolves the effective delivery provider for a workspace and channel, fail-closed. For this slice
 * email resolves through the workspace mail transport ({@link MailConfigResolver}); there is no
 * {@code delivery_provider_config} table yet, so {@link #isReady} for email is exactly "a usable
 * workspace or instance mail config exists". The seam is kept so a DB-backed provider config can be
 * substituted later without changing callers.
 */
@Service
@RequiredArgsConstructor
public class DeliveryProviderConfigService implements DeliveryProviderReadiness {

    private final MailConfigResolver mailConfigResolver;

    /**
     * Resolves the provider a workspace should use for a channel.
     * @param workspaceId the workspace
     * @param channel the delivery channel
     * @return the resolved provider
     * @throws DeliveryProviderException when the channel is unsupported or no transport is configured
     */
    public ResolvedDeliveryProvider resolveForWorkspace(int workspaceId, DeliveryChannel channel) {
        requireEmail(channel);
        ResolvedMailConfig config = mailConfigResolver.resolveForWorkspace(workspaceId);
        if (config == null || !config.usable()) {
            throw new DeliveryProviderException("No usable mail transport is configured for delivery");
        }
        return new ResolvedDeliveryProvider(
                SmtpDeliveryProvider.PROVIDER_ID, DeliveryChannel.EMAIL, workspaceId, DeliveryCredentials.none());
    }

    @Override
    public boolean isReady(int workspaceId, DeliveryChannel channel) {
        if (channel != DeliveryChannel.EMAIL) {
            return false;
        }
        ResolvedMailConfig config = mailConfigResolver.resolveForWorkspace(workspaceId);
        return config != null && config.usable();
    }

    private static void requireEmail(DeliveryChannel channel) {
        if (channel != DeliveryChannel.EMAIL) {
            throw new DeliveryProviderException("Delivery channel " + channel + " is not supported yet");
        }
    }
}
