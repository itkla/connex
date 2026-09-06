package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.CampaignSend;

/** Data access for workspace-scoped campaign sends. */
public interface CampaignSendMapper {

    List<CampaignSend> getSendsByCampaign(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId);

    CampaignSend getSend(@Param("workspaceId") int workspaceId, @Param("id") int id);

    CampaignSend getSendForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Returns the long-lived triggered send for one immutable message revision. */
    CampaignSend getTriggeredSend(
            @Param("workspaceId") int workspaceId,
            @Param("messageId") int messageId,
            @Param("messageVersion") int messageVersion);

    void insertSend(CampaignSend send);

    int transitionStatus(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    int markRunning(@Param("workspaceId") int workspaceId, @Param("id") int id);

    int assignProvider(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("providerId") String providerId);

    int markCompleted(@Param("workspaceId") int workspaceId, @Param("id") int id);

    int refreshCounters(@Param("workspaceId") int workspaceId, @Param("id") int id);

    List<Integer> queuedSendIds(
            @Param("workspaceId") int workspaceId,
            @Param("triggeredSendEnabled") boolean triggeredSendEnabled);

    List<Integer> workspaceIdsWithQueuedSends(
            @Param("triggeredSendEnabled") boolean triggeredSendEnabled);
}
