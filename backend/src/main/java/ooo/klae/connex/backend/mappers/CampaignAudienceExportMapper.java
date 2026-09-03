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

    boolean existsActiveForSnapshotConnector(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId,
            @Param("snapshotId") int snapshotId,
            @Param("connector") String connector);

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
