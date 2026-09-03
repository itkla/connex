package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Current campaign audience estimate after ordered policy exclusions.
 * @param channel delivery channel used for the estimate
 * @param purpose consent purpose used for the estimate
 * @param estimatedIncluded included records
 * @param excludedNoAddress records without a usable address on the channel
 * @param excludedConsent records blocked by the active consent policy
 * @param excludedSuppressed suppressed records
 * @param excludedRestricted processing-restricted records
 * @param excludedTotal total excluded records
 * @param sampleLabels bounded sample of included record labels
 */
public record CampaignAudienceEstimateDto(
        String channel,
        String purpose,
        int estimatedIncluded,
        int excludedNoAddress,
        int excludedConsent,
        int excludedSuppressed,
        int excludedRestricted,
        int excludedTotal,
        List<RecordLabelDto> sampleLabels) {
    public CampaignAudienceEstimateDto {
        sampleLabels = List.copyOf(sampleLabels);
    }
}
