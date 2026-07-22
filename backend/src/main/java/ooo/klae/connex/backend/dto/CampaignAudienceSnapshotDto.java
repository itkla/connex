package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Immutable campaign audience snapshot with its frozen definition and members.
 * @param campaignId campaign id
 * @param version campaign-local version
 * @param recordType record type
 * @param definition frozen smart-segment definition
 * @param estimatedIncluded included count
 * @param excludedTotal total excluded count
 * @param excludedConsent consent exclusion count
 * @param excludedSuppressed suppression exclusion count
 * @param excludedRestricted restriction exclusion count
 * @param createdById creator id
 * @param createdAt creation timestamp
 * @param members frozen classified members
 */
public record CampaignAudienceSnapshotDto(
        int campaignId,
        int version,
        String recordType,
        SegmentDefinition definition,
        int estimatedIncluded,
        int excludedTotal,
        int excludedConsent,
        int excludedSuppressed,
        int excludedRestricted,
        Integer createdById,
        LocalDateTime createdAt,
        List<CampaignAudienceMemberDto> members) {
    public CampaignAudienceSnapshotDto {
        members = List.copyOf(members);
    }
}
