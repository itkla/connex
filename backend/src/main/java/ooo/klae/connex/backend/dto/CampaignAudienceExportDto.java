package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import ooo.klae.connex.backend.beans.CampaignAudienceExport;

/**
 * A campaign audience export as returned to the client: the connector it targeted, the snapshot it
 * pushed, its status, and the eligible/pushed/failed tallies.
 * @param id the export id
 * @param campaignId the campaign
 * @param snapshotId the frozen snapshot pushed
 * @param connector the connector id
 * @param externalListId the external list the audience was synced into, or null
 * @param status the export status
 * @param totalMembers the eligible member total after the re-check
 * @param pushedCount the members the connector accepted
 * @param failedCount the members the connector rejected
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
        int pushedCount,
        int failedCount,
        Integer createdById,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * Maps a stored export to its client view.
     * @param export the stored export
     * @return the DTO
     */
    public static CampaignAudienceExportDto from(CampaignAudienceExport export) {
        return new CampaignAudienceExportDto(
                export.getId(), export.getCampaignId(), export.getSnapshotId(), export.getConnector(),
                export.getExternalListId(), export.getStatus(), export.getTotalMembers(),
                export.getPushedCount(), export.getFailedCount(), export.getCreatedById(),
                export.getCreatedAt(), export.getUpdatedAt());
    }
}
