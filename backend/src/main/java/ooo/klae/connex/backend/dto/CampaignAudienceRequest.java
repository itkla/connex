package ooo.klae.connex.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Active smart-segment audience definition for a campaign.
 * @param recordType person, company, or deal
 * @param definition smart-segment definition
 */
public record CampaignAudienceRequest(
        @NotBlank @Pattern(regexp = "person|company|deal") String recordType,
        @NotNull @Valid SegmentDefinition definition) {
}
