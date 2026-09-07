package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import lombok.Data;
import ooo.klae.connex.backend.beans.DeliveryProviderConfig;

/**
 * A workspace's delivery provider settings for one channel, as returned to the client. The send
 * credential, the webhook signing secret, and the raw webhook token are never included; only masked
 * metadata and presence flags are exposed so the settings UI can render a configured state.
 */
@Data
public class DeliveryProviderConfigDto {
    private String channel;
    private String provider;
    private String endpoint;
    private String fromAddress;
    private String fromName;
    private boolean hasCredential;
    private String credentialLast4;
    private boolean webhookConfigured;
    private boolean enabled;
    private boolean idempotentSubmission;
    private LocalDateTime updatedAt;

    /**
     * Maps a stored config to its client view, omitting every secret.
     * @param config the stored config
     * @return the masked DTO
     */
    public static DeliveryProviderConfigDto from(DeliveryProviderConfig config) {
        DeliveryProviderConfigDto dto = new DeliveryProviderConfigDto();
        dto.setChannel(config.getChannel());
        dto.setProvider(config.getProvider());
        dto.setEndpoint(config.getEndpoint());
        dto.setFromAddress(config.getFromAddress());
        dto.setFromName(config.getFromName());
        dto.setHasCredential(config.getCredentialRef() != null && !config.getCredentialRef().isBlank());
        dto.setCredentialLast4(config.getCredentialLast4());
        dto.setWebhookConfigured(config.getWebhookTokenHash() != null && !config.getWebhookTokenHash().isBlank());
        dto.setEnabled(config.isEnabled());
        dto.setIdempotentSubmission(config.isIdempotentSubmission());
        dto.setUpdatedAt(config.getUpdatedAt());
        return dto;
    }
}
