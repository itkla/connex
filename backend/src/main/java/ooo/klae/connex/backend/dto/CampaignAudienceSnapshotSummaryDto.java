package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * Summary of an immutable campaign audience snapshot.
 * @param version campaign-local snapshot version
 * @param recordType record type
 * @param channel delivery channel used for classification
 * @param purpose consent purpose used for classification
 * @param estimatedIncluded included count
 * @param excludedTotal total excluded count
 * @param excludedNoAddress missing-address exclusion count
 * @param excludedConsent consent exclusion count
 * @param excludedSuppressed suppression exclusion count
 * @param excludedRestricted restriction exclusion count
 * @param createdById creator id
 * @param createdAt creation timestamp
 */
public record CampaignAudienceSnapshotSummaryDto(
        int version,
        String recordType,
        String channel,
        String purpose,
        int estimatedIncluded,
        int excludedTotal,
        int excludedNoAddress,
        int excludedConsent,
        int excludedSuppressed,
        int excludedRestricted,
        Integer createdById,
        LocalDateTime createdAt) {
}
