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

    void insertExport(CampaignAudienceExport export);

    int updateOutcome(CampaignAudienceExport export);
}
