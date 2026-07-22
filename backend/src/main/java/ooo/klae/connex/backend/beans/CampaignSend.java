package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** A campaign send bound to a frozen audience snapshot and a chosen message version. */
@Data
@NoArgsConstructor
public class CampaignSend {
    private int id;
    private int workspaceId;
    private int campaignId;
    private int snapshotId;
    private int messageId;
    private int messageVersion;
    private String channel;
    private String purpose;
    private String providerId;
    private String status;
    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private int totalRecipients;
    private int dispatchedCount;
    private int skippedCount;
    private int failedCount;
    private Integer createdById;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
