package ooo.klae.connex.backend.services;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.dto.CampaignChannelStatDto;
import ooo.klae.connex.backend.dto.CampaignEngagementCountRow;
import ooo.klae.connex.backend.dto.CampaignEngagementDto;
import ooo.klae.connex.backend.dto.CampaignSendEngagementDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CampaignEngagementMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Read-only engagement and attribution reporting over materialized campaign deliveries and
 * their append-only lifecycle events. Every rollup is workspace-scoped through
 * {@link WorkspaceService#getCurrentWorkspaceId()} and the mapper's bound
 * {@code #{workspaceId}} predicate; the campaign is resolved workspace-scoped first, so a
 * cross-tenant caller gets a not-found rather than another workspace's numbers. No opens are
 * tracked, and delivery/bounce/complaint outcomes are reported only when the provider
 * actually supplies receipts.
 */
@Service
@RequiredArgsConstructor
public class CampaignEngagementService {

    private final CampaignMapper campaignMapper;
    private final CampaignSendMapper campaignSendMapper;
    private final CampaignEngagementMapper campaignEngagementMapper;
    private final WorkspaceService workspaceService;

    /** Aggregates engagement across every send of a campaign, plus a per-send breakdown. */
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public CampaignEngagementDto getCampaignEngagement(int campaignId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCampaign(workspaceId, campaignId);
        Map<String, Integer> statusCounts =
                toMap(campaignEngagementMapper.deliveryStatusCountsForCampaign(workspaceId, campaignId));
        Map<String, Integer> skipReasons =
                toMap(campaignEngagementMapper.skipReasonCountsForCampaign(workspaceId, campaignId));
        Map<String, Integer> eventCounts =
                toMap(campaignEngagementMapper.eventTypeCountsForCampaign(workspaceId, campaignId));
        List<CampaignChannelStatDto> channels =
                campaignEngagementMapper.channelSplit(workspaceId, campaignId).stream()
                        .map(row -> new CampaignChannelStatDto(row.keyValue(), row.countValue()))
                        .toList();
        List<CampaignSend> sends = campaignSendMapper.getSendsByCampaign(workspaceId, campaignId);
        List<CampaignSendEngagementDto> sendRollups = sends.stream()
                .map(send -> sendEngagement(workspaceId, send))
                .toList();
        int totalRecipients = sends.stream().mapToInt(CampaignSend::getTotalRecipients).sum();
        DeliveryMetrics metrics = metrics(statusCounts, eventCounts);
        return new CampaignEngagementDto(
                campaignId, totalRecipients, metrics.dispatched(), metrics.delivered(), metrics.bounced(),
                metrics.complained(), metrics.unsubscribed(), metrics.failed(), metrics.skipped(),
                skipReasons, eventCounts, channels, metrics.deliveryReceiptsAvailable(),
                metrics.deliveryRate(), metrics.bounceRate(), metrics.complaintRate(), sendRollups);
    }

    /** Returns the engagement rollup for a single send of a campaign. */
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public CampaignSendEngagementDto getSendEngagement(int campaignId, int sendId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCampaign(workspaceId, campaignId);
        return sendEngagement(workspaceId, requireSend(workspaceId, campaignId, sendId));
    }

    private CampaignSendEngagementDto sendEngagement(int workspaceId, CampaignSend send) {
        Map<String, Integer> statusCounts =
                toMap(campaignEngagementMapper.deliveryStatusCountsForSend(workspaceId, send.getId()));
        Map<String, Integer> skipReasons =
                toMap(campaignEngagementMapper.skipReasonCountsForSend(workspaceId, send.getId()));
        Map<String, Integer> eventCounts =
                toMap(campaignEngagementMapper.eventTypeCountsForSend(workspaceId, send.getId()));
        DeliveryMetrics metrics = metrics(statusCounts, eventCounts);
        return new CampaignSendEngagementDto(
                send.getId(), send.getStatus(), send.getChannel(), send.getTotalRecipients(),
                metrics.dispatched(), metrics.delivered(), metrics.bounced(), metrics.complained(),
                metrics.unsubscribed(), metrics.failed(), metrics.skipped(), skipReasons, eventCounts,
                metrics.deliveryReceiptsAvailable(), metrics.deliveryRate(), metrics.bounceRate(),
                metrics.complaintRate());
    }

    /**
     * Derives the named engagement counts and rates. Delivered, bounced, and complained are
     * read from the current delivery status; {@code unsubscribed} is read from events, as it
     * is never a delivery status. Receipts are considered available only when a provider
     * emitted a delivery, bounce, or complaint event — the plain SMTP path emits none, so its
     * outcome counts stay zero by absence of measurement and the rates are left {@code null}
     * rather than presented as a measured 0%.
     */
    private static DeliveryMetrics metrics(Map<String, Integer> statusCounts, Map<String, Integer> eventCounts) {
        int dispatched = statusCounts.getOrDefault("dispatched", 0);
        int delivered = statusCounts.getOrDefault("delivered", 0);
        int bounced = statusCounts.getOrDefault("bounced", 0);
        int complained = statusCounts.getOrDefault("complained", 0);
        int failed = statusCounts.getOrDefault("failed", 0);
        int skipped = statusCounts.getOrDefault("skipped", 0);
        int unsubscribed = eventCounts.getOrDefault("unsubscribed", 0);
        boolean receiptsAvailable = eventCounts.getOrDefault("delivered", 0) > 0
                || eventCounts.getOrDefault("bounced", 0) > 0
                || eventCounts.getOrDefault("complained", 0) > 0;
        int handedOff = dispatched + delivered + bounced + complained;
        Double deliveryRate = rate(receiptsAvailable, delivered, handedOff);
        Double bounceRate = rate(receiptsAvailable, bounced, handedOff);
        Double complaintRate = rate(receiptsAvailable, complained, handedOff);
        return new DeliveryMetrics(dispatched, delivered, bounced, complained, unsubscribed, failed,
                skipped, receiptsAvailable, deliveryRate, bounceRate, complaintRate);
    }

    private static Double rate(boolean receiptsAvailable, int numerator, int handedOff) {
        if (!receiptsAvailable || handedOff == 0) {
            return null;
        }
        return (double) numerator / handedOff;
    }

    private static Map<String, Integer> toMap(List<CampaignEngagementCountRow> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (CampaignEngagementCountRow row : rows) {
            if (row.keyValue() != null) {
                counts.put(row.keyValue(), row.countValue());
            }
        }
        return counts;
    }

    private Campaign requireCampaign(int workspaceId, int campaignId) {
        Campaign campaign = campaignMapper.getCampaign(workspaceId, campaignId);
        if (campaign == null) {
            throw new ResourceNotFoundException("Campaign not found with id: " + campaignId);
        }
        return campaign;
    }

    private CampaignSend requireSend(int workspaceId, int campaignId, int sendId) {
        CampaignSend send = campaignSendMapper.getSend(workspaceId, sendId);
        if (send == null || send.getCampaignId() != campaignId) {
            throw new ResourceNotFoundException("Campaign send not found with id: " + sendId);
        }
        return send;
    }

    private record DeliveryMetrics(
            int dispatched,
            int delivered,
            int bounced,
            int complained,
            int unsubscribed,
            int failed,
            int skipped,
            boolean deliveryReceiptsAvailable,
            Double deliveryRate,
            Double bounceRate,
            Double complaintRate) {
    }
}
