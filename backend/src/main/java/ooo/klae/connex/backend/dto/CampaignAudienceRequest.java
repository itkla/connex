package ooo.klae.connex.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Active smart-segment audience definition for a campaign.
 * @param recordType person, company, or deal
 * @param definition smart-segment definition
 * @param channel delivery channel, defaulting to email when omitted
 * @param purpose consent purpose, defaulting to marketing when omitted
 */
public record CampaignAudienceRequest(
        @NotBlank @Pattern(regexp = "person|company|deal") String recordType,
        @NotNull @Valid SegmentDefinition definition,
        @Pattern(regexp = "email|sms") String channel,
        @Pattern(regexp = "[a-z][a-z0-9_-]{0,31}") String purpose) {

    /** Creates a request using the default email and marketing classification scope. */
    public CampaignAudienceRequest(String recordType, SegmentDefinition definition) {
        this(recordType, definition, null, null);
    }
}
