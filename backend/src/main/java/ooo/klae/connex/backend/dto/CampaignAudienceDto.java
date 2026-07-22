package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * Typed active audience definition returned after it is stored.
 * @param campaignId campaign id
 * @param recordType person, company, or deal
 * @param definition smart-segment definition
 * @param mode audience mode
 * @param updatedAt last update timestamp
 */
public record CampaignAudienceDto(
        int campaignId,
        String recordType,
        SegmentDefinition definition,
        String mode,
        LocalDateTime updatedAt) {
}
