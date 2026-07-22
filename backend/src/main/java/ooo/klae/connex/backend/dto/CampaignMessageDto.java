package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * API representation of a campaign message and its revisions.
 * @param id the message id
 * @param campaignId the owning campaign id
 * @param channel the delivery channel
 * @param name the message name
 * @param status the message status
 * @param createdById the creator id
 * @param createdAt the creation timestamp
 * @param updatedAt the update timestamp
 * @param revisions the message's immutable revisions, newest first
 */
public record CampaignMessageDto(
        int id,
        int campaignId,
        String channel,
        String name,
        String status,
        Integer createdById,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<CampaignMessageRevisionDto> revisions) {
}
