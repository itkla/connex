package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * Current campaign audience estimate after ordered policy exclusions.
 * @param estimatedIncluded included records
 * @param excludedConsent records missing required consent
 * @param excludedSuppressed suppressed records
 * @param excludedRestricted processing-restricted records
 * @param excludedTotal total excluded records
 * @param sampleLabels bounded sample of included record labels
 */
public record CampaignAudienceEstimateDto(
        int estimatedIncluded,
        int excludedConsent,
        int excludedSuppressed,
        int excludedRestricted,
        int excludedTotal,
        List<RecordLabelDto> sampleLabels) {
    public CampaignAudienceEstimateDto {
        sampleLabels = List.copyOf(sampleLabels);
    }
}
