package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Frozen recipient identity, token hash, and decision evidence for one envelope. */
@Data
@NoArgsConstructor
public class DocumentDeliveryRecipient {
    private int id;
    private int workspaceId;
    private int deliveryId;
    private Integer personId;
    private String name;
    private String email;
    private String role;
    private int recipientOrder;
    private String status;
    private String tokenHash;
    private LocalDateTime tokenExpiresAt;
    private String providerRecipientId;
    private LocalDateTime firstViewedAt;
    private LocalDateTime decidedAt;
    private String typedName;
    private String declineReason;
    private String evidenceIpHash;
    private String evidenceAgentHash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
