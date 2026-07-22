package ooo.klae.connex.backend.dto;

import java.util.List;
import java.util.Map;

/**
 * Deterministic engagement rollup for a whole campaign, aggregated across its sends.
 *
 * <p>The named counts are read from the delivery pipeline's current status, except
 * {@code unsubscribed}, which is only ever an append-only event and never a delivery
 * status. {@code deliveryReceiptsAvailable} is {@code true} only when a provider reported
 * at least one delivery, bounce, or complaint event for the campaign; on the plain SMTP
 * path no such receipts exist, so {@code delivered}/{@code bounced}/{@code complained} are
 * zero by absence of measurement rather than by measurement, and the rate fields are
 * {@code null} so a caller can render "not measured" instead of a misleading 0%. Opens are
 * never tracked and never reported. Rates are fractions in {@code [0, 1]}.
 *
 * @param campaignId the campaign id
 * @param totalRecipients the materialized recipient count across all sends
 * @param dispatched deliveries currently in the dispatched status
 * @param delivered deliveries a provider confirmed delivered
 * @param bounced deliveries a provider reported bounced
 * @param complained deliveries a recipient reported as spam
 * @param unsubscribed recipients who unsubscribed, counted from events
 * @param failed deliveries that failed to dispatch
 * @param skipped deliveries skipped before dispatch
 * @param skipReasons skipped-delivery counts by skip reason
 * @param eventCounts append-only lifecycle event counts by event type
 * @param channels per-channel delivery volume
 * @param deliveryReceiptsAvailable whether provider delivery receipts exist for the campaign
 * @param deliveryRate delivered over handed-off, or {@code null} when not measured
 * @param bounceRate bounced over handed-off, or {@code null} when not measured
 * @param complaintRate complained over handed-off, or {@code null} when not measured
 * @param sends per-send engagement rollups
 */
public record CampaignEngagementDto(
        int campaignId,
        int totalRecipients,
        int dispatched,
        int delivered,
        int bounced,
        int complained,
        int unsubscribed,
        int failed,
        int skipped,
        Map<String, Integer> skipReasons,
        Map<String, Integer> eventCounts,
        List<CampaignChannelStatDto> channels,
        boolean deliveryReceiptsAvailable,
        Double deliveryRate,
        Double bounceRate,
        Double complaintRate,
        List<CampaignSendEngagementDto> sends) {
}
