package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import ooo.klae.connex.backend.beans.CampaignAudienceExport;

/**
 * A campaign audience export as returned to the client: the connector it targeted, the snapshot it
 * pushed, its status, and its stable prepared total. Provider outcome counts are present only when
 * the caller holds {@code CONSENT_MANAGE} and the outcome is known.
 * @param id the export id
 * @param campaignId the campaign
 * @param snapshotId the frozen snapshot pushed
 * @param connector the connector id
 * @param externalListId the external list the audience was synced into, or null
 * @param status the export status
 * @param totalMembers the member total admitted and frozen by export preparation
 * @param pushedCount the members the connector accepted, or null when detailed counts are unavailable
 * @param failedCount the members not pushed, or null when detailed counts are unavailable
 * @param detailedCountsKnown whether the provider outcome counts are historically known
 * @param detailedCountsAvailable whether pushed and failed counts are safe and available to this caller
 * @param createdById the actor who created the export, or null
 * @param createdAt when the export was created
 * @param updatedAt when the export last changed
 */
public record CampaignAudienceExportDto(
        int id,
        int campaignId,
        int snapshotId,
        String connector,
        String externalListId,
        String status,
        int totalMembers,
        Integer pushedCount,
        Integer failedCount,
        boolean detailedCountsKnown,
        boolean detailedCountsAvailable,
        Integer createdById,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * Maps a stored export to its client view.
     * @param export the stored export
     * @param includeDetailedCounts whether the caller may receive eligibility-derived outcome counts
     * @return the DTO
     */
    public static CampaignAudienceExportDto from(
            CampaignAudienceExport export, boolean includeDetailedCounts) {
        boolean detailsKnown = export.getPushedCount() != null && export.getFailedCount() != null;
        boolean detailsAvailable = detailsKnown && includeDetailedCounts
                && !"running".equals(export.getStatus())
                && !"needs_reconciliation".equals(export.getStatus());
        return new CampaignAudienceExportDto(
                export.getId(), export.getCampaignId(), export.getSnapshotId(), export.getConnector(),
                export.getExternalListId(), export.getStatus(), export.getTotalMembers(),
                detailsAvailable ? export.getPushedCount() : null,
                detailsAvailable ? export.getFailedCount() : null,
                detailsKnown, detailsAvailable, export.getCreatedById(),
                export.getCreatedAt(), export.getUpdatedAt());
    }
}
