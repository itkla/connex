package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.dto.CampaignEngagementCountRow;

/**
 * Read-only workspace-scoped aggregation over materialized campaign deliveries and their
 * append-only events. Every statement binds {@code #{workspaceId}} and grouping is done in
 * SQL; per-campaign variants join {@code campaign_delivery} to {@code campaign_send} on
 * {@code (workspace_id, send_id)} and filter by campaign. SQL lives in
 * {@code resources/mappers/CampaignEngagementMapper.xml}.
 */
public interface CampaignEngagementMapper {

    List<CampaignEngagementCountRow> deliveryStatusCountsForSend(
            @Param("workspaceId") int workspaceId,
            @Param("sendId") int sendId);

    List<CampaignEngagementCountRow> deliveryStatusCountsForCampaign(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId);

    List<CampaignEngagementCountRow> skipReasonCountsForSend(
            @Param("workspaceId") int workspaceId,
            @Param("sendId") int sendId);

    List<CampaignEngagementCountRow> skipReasonCountsForCampaign(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId);

    List<CampaignEngagementCountRow> eventTypeCountsForSend(
            @Param("workspaceId") int workspaceId,
            @Param("sendId") int sendId);

    List<CampaignEngagementCountRow> eventTypeCountsForCampaign(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId);

    List<CampaignEngagementCountRow> channelSplit(
            @Param("workspaceId") int workspaceId,
            @Param("campaignId") int campaignId);
}
