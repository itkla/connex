package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * Summary of an immutable campaign audience snapshot.
 * @param version campaign-local snapshot version
 * @param recordType record type
 * @param estimatedIncluded included count
 * @param excludedTotal total excluded count
 * @param excludedConsent consent exclusion count
 * @param excludedSuppressed suppression exclusion count
 * @param excludedRestricted restriction exclusion count
 * @param createdById creator id
 * @param createdAt creation timestamp
 */
public record CampaignAudienceSnapshotSummaryDto(
        int version,
        String recordType,
        int estimatedIncluded,
        int excludedTotal,
        int excludedConsent,
        int excludedSuppressed,
        int excludedRestricted,
        Integer createdById,
        LocalDateTime createdAt) {
}
