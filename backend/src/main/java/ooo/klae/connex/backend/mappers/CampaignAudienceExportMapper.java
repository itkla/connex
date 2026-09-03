package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.CampaignAudienceExport;

/**
 * Data access for workspace-scoped campaign audience exports. Every read and write binds an explicit
 * {@code workspaceId}. SQL lives in {@code resources/mappers/CampaignAudienceExportMapper.xml}.
 */
public interface CampaignAudienceExportMapper {

    List<CampaignAudienceExport> getByCampaign(
            @Param("workspaceId") int workspaceId, @Param("campaignId") int campaignId);

    CampaignAudienceExport getExport(@Param("workspaceId") int workspaceId, @Param("id") int id);

    CampaignAudienceExport getExportForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /**
     * Tests the duplicate fence. Every running row is active, including a legacy row whose lease is
     * null and therefore cannot be classified as stale automatically.
     * @param workspaceId workspace scope
     * @param campaignId owning campaign
     * @param snapshotId immutable audience snapshot
     * @param connector connector id
     * @return whether an active or potentially delivered export blocks replacement
     */
    boolean existsActiveForSnapshotConnector(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId,
            @Param("snapshotId") int snapshotId,
            @Param("connector") String connector);

    /**
     * Transitions only running rows with an expired nonnull lease. Legacy null-lease rows remain
     * running because their in-flight lifetime is unknown.
     * @param workspaceId workspace scope
     * @param campaignId owning campaign
     * @param exportIds candidate export ids
     * @return the number of expired rows transitioned
     */
    int markStaleRunningNeedsReconciliation(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId,
            @Param("exportIds") List<Integer> exportIds);

    int nextAttemptForSnapshotTarget(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId,
            @Param("snapshotId") int snapshotId,
            @Param("connector") String connector,
            @Param("externalListId") String externalListId);

    void insertExport(CampaignAudienceExport export);

    int stagePush(CampaignAudienceExport export);

    int updateOutcome(CampaignAudienceExport export);

    int resolveReconciliation(CampaignAudienceExport export);
}
