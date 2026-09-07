package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * API representation of a campaign send.
 * @param id the send id
 * @param campaignId the owning campaign id
 * @param snapshotId the frozen audience or triggered-revision snapshot id
 * @param origin whether the send is audience-backed or triggered
 * @param messageId the message id
 * @param messageVersion the message revision version
 * @param channel the delivery channel
 * @param purpose the enforced consent purpose
 * @param providerId the resolved provider id, or null before dispatch
 * @param status the send lifecycle status
 * @param scheduledAt the optional scheduled dispatch time
 * @param startedAt the dispatch start time, or null
 * @param completedAt the dispatch completion time, or null
 * @param totalRecipients the materialized recipient count
 * @param dispatchedCount the dispatched recipient count
 * @param skippedCount the skipped recipient count
 * @param failedCount the failed recipient count
 * @param createdById the creator id
 * @param createdAt the creation timestamp
 * @param updatedAt the update timestamp
 */
public record CampaignSendDto(
        int id,
        int campaignId,
        int snapshotId,
        String origin,
        int messageId,
        int messageVersion,
        String channel,
        String purpose,
        String providerId,
        String status,
        LocalDateTime scheduledAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        int totalRecipients,
        int dispatchedCount,
        int skippedCount,
        int failedCount,
        Integer createdById,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
