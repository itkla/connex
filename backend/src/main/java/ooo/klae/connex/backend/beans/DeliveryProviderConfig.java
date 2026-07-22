package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A workspace's per-channel outbound delivery provider selection. The send credential and inbound
 * webhook signing secret live in the central secret store; this bean carries only their opaque
 * references and masked metadata plus the SHA-256 of the webhook URL token.
 */
@Data
@NoArgsConstructor
@ToString(exclude = {"credentialRef", "webhookSecretRef", "webhookTokenHash"})
public class DeliveryProviderConfig {
    private int id;
    private int workspaceId;
    private String channel;
    private String provider;
    private String endpoint;
    private String fromAddress;
    private String fromName;
    private String credentialRef;
    private String credentialLast4;
    private String webhookTokenHash;
    private String webhookSecretRef;
    private boolean enabled;
    private Integer createdById;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
