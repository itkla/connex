package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * Typed active audience definition returned after it is stored.
 * @param campaignId campaign id
 * @param recordType person, company, or deal
 * @param definition smart-segment definition
 * @param mode audience mode
 * @param channel delivery channel used to classify the audience
 * @param purpose consent purpose used to classify the audience
 * @param updatedAt last update timestamp
 */
public record CampaignAudienceDto(
        int campaignId,
        String recordType,
        SegmentDefinition definition,
        String mode,
        String channel,
        String purpose,
        LocalDateTime updatedAt) {
}
