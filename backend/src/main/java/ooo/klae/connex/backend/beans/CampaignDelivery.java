package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** A per-recipient materialized campaign delivery row. */
@Data
@NoArgsConstructor
public class CampaignDelivery {
    private int id;
    private int workspaceId;
    private int sendId;
    private Integer personId;
    private String address;
    private String status;
    private String skipReason;
    private String providerMessageId;
    private int attemptCount;
    private String lastError;
    private String unsubscribeToken;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
