package ooo.klae.connex.backend.dto;

/**
 * Frozen classified member of a campaign audience snapshot.
 * @param recordType record type
 * @param recordId record id
 * @param status included or excluded
 * @param exclusionReason ordered exclusion reason when excluded
 */
public record CampaignAudienceMemberDto(
        String recordType,
        int recordId,
        String status,
        String exclusionReason) {
}
