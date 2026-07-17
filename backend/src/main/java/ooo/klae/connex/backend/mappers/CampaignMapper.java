package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.CampaignAudience;
import ooo.klae.connex.backend.beans.CampaignAudienceMember;
import ooo.klae.connex.backend.beans.CampaignAudienceSnapshot;
import ooo.klae.connex.backend.dto.CampaignAudienceSnapshotSummaryDto;

/** Data access for workspace-scoped campaigns and immutable audience snapshots. */
public interface CampaignMapper {
    List<Campaign> getCampaigns(@Param("workspaceId") int workspaceId);

    Campaign getCampaign(@Param("workspaceId") int workspaceId, @Param("id") int id);

    Campaign getCampaignForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);

    void insertCampaign(Campaign campaign);

    int updateCampaign(Campaign campaign);

    int clearParentReferences(@Param("workspaceId") int workspaceId, @Param("parentCampaignId") int parentCampaignId);

    int deleteCampaign(@Param("workspaceId") int workspaceId, @Param("id") int id);

    CampaignAudience getAudience(@Param("workspaceId") int workspaceId, @Param("campaignId") int campaignId);

    void upsertAudience(CampaignAudience audience);

    int nextSnapshotVersion(@Param("workspaceId") int workspaceId, @Param("campaignId") int campaignId);

    void insertSnapshot(CampaignAudienceSnapshot snapshot);

    List<CampaignAudienceSnapshotSummaryDto> getSnapshots(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId);

    CampaignAudienceSnapshot getSnapshot(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId,
            @Param("version") int version);

    List<CampaignAudienceMember> getSnapshotMembers(
            @Param("workspaceId") int workspaceId,
            @Param("snapshotId") int snapshotId);

    int insertSnapshotMembers(
            @Param("workspaceId") int workspaceId,
            @Param("members") List<CampaignAudienceMember> members);

    List<Integer> restrictedPersonIds(
            @Param("workspaceId") int workspaceId,
            @Param("ids") List<Integer> ids);

    List<Integer> suppressedPersonIds(
            @Param("workspaceId") int workspaceId,
            @Param("ids") List<Integer> ids,
            @Param("channel") String channel);

    List<Integer> grantedConsentPersonIds(
            @Param("workspaceId") int workspaceId,
            @Param("ids") List<Integer> ids,
            @Param("channel") String channel,
            @Param("purpose") String purpose);

    List<Integer> revokedConsentPersonIds(
            @Param("workspaceId") int workspaceId,
            @Param("ids") List<Integer> ids,
            @Param("channel") String channel,
            @Param("purpose") String purpose);

    List<String> suppressedAddresses(
            @Param("workspaceId") int workspaceId,
            @Param("channel") String channel,
            @Param("addresses") List<String> addresses);

    int clearMemberOwnership(@Param("workspaceId") int workspaceId, @Param("userId") int userId);

    void clearCampaignUserReferencesAnywhere(@Param("userId") int userId);

    void clearSnapshotCreatorsAnywhere(@Param("userId") int userId);
}
