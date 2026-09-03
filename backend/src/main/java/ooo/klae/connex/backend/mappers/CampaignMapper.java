package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.CampaignAudience;
import ooo.klae.connex.backend.beans.CampaignAudienceMember;
import ooo.klae.connex.backend.beans.CampaignAudienceSnapshot;
import ooo.klae.connex.backend.dto.CampaignAudienceSnapshotSummaryDto;
import ooo.klae.connex.backend.dto.CampaignSummaryDto;

/** Data access for workspace-scoped campaigns and immutable audience snapshots. */
public interface CampaignMapper {
    List<Campaign> getCampaigns(@Param("workspaceId") int workspaceId);

    /**
     * Bounded global-search slice of the campaign list, matched on name and objective.
     *
     * @param workspaceId the resolved tenant
     * @param query the escaped {@code LIKE} pattern
     * @return at most ten matching campaigns, ordered by name
     */
    List<CampaignSummaryDto> searchCampaigns(
            @Param("workspaceId") int workspaceId,
            @Param("query") String query);

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

    /** Returns the synthetic snapshot that keeps one triggered revision rollback-readable. */
    CampaignAudienceSnapshot getTriggeredSnapshot(
            @Param("workspaceId") int workspaceId,
            @Param("messageId") int messageId,
            @Param("messageVersion") int messageVersion);

    List<CampaignAudienceSnapshotSummaryDto> getSnapshots(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId);

    CampaignAudienceSnapshot getSnapshot(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId,
            @Param("version") int version);

    /** Returns a current immutable snapshot while retaining a shared lock through the transaction. */
    CampaignAudienceSnapshot getSnapshotForShare(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId,
            @Param("version") int version);

    List<CampaignAudienceMember> getSnapshotMembers(
            @Param("workspaceId") int workspaceId,
            @Param("snapshotId") int snapshotId);

    /** Returns current immutable snapshot members under shared locks. */
    List<CampaignAudienceMember> getSnapshotMembersForShare(
            @Param("workspaceId") int workspaceId,
            @Param("snapshotId") int snapshotId);

    int insertSnapshotMembers(
            @Param("workspaceId") int workspaceId,
            @Param("members") List<CampaignAudienceMember> members);

    List<Integer> restrictedPersonIds(
            @Param("workspaceId") int workspaceId,
            @Param("ids") List<Integer> ids);

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

    List<Integer> suppressedPersonRefIds(
            @Param("workspaceId") int workspaceId,
            @Param("ids") List<Integer> ids,
            @Param("channel") String channel);

    int clearMemberOwnership(@Param("workspaceId") int workspaceId, @Param("userId") int userId);

    void clearCampaignUserReferencesAnywhere(@Param("userId") int userId);

    void clearSnapshotCreatorsAnywhere(@Param("userId") int userId);
}
