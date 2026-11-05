package ooo.klae.connex.backend.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Deterministic engagement rollup for a single campaign send.
 *
 * <p>Outcome counts use the delivery pipeline's current status. {@code dispatched} counts
 * persisted confirmed submissions, including operator-confirmed acceptance and later failures;
 * {@code unsubscribed} counts unsubscribe events.
 * {@code deliveryReceiptsAvailable} is {@code true} only when the send's provider
 * reported at least one delivery, bounce, or complaint event; on the plain SMTP path no
 * such receipts exist, so {@code delivered}/{@code bounced}/{@code complained} are zero by
 * absence of measurement rather than by measurement, and the rate fields are {@code null}
 * so a caller can render "not measured" instead of a misleading 0%. Opens are never tracked
 * and never reported. Rates are fractions in {@code [0, 1]}.
 *
 * @param sendId the send id
 * @param status the send lifecycle status
 * @param channel the delivery channel
 * @param preparedRecipients prepared recipients, including draft sends
 * @param dispatched confirmed submissions, regardless of current delivery status
 * @param delivered deliveries a provider confirmed delivered
 * @param bounced deliveries a provider reported bounced
 * @param complained deliveries a recipient reported as spam
 * @param unsubscribed recipients who unsubscribed, counted from events
 * @param failed deliveries that failed to dispatch
 * @param skipped deliveries skipped before dispatch
 * @param skipReasons skipped-delivery counts by skip reason
 * @param eventCounts append-only lifecycle event counts by event type
 * @param deliveryReceiptsAvailable whether provider delivery receipts exist for this send
 * @param deliveryRate delivered over handed-off, or {@code null} when not measured
 * @param bounceRate bounced over handed-off, or {@code null} when not measured
 * @param complaintRate complained over handed-off, or {@code null} when not measured
 */
public record CampaignSendEngagementDto(
        int sendId,
        String status,
        String channel,
        int preparedRecipients,
        int dispatched,
        int delivered,
        int bounced,
        int complained,
        int unsubscribed,
        int failed,
        int skipped,
        Map<String, Integer> skipReasons,
        Map<String, Integer> eventCounts,
        boolean deliveryReceiptsAvailable,
        Double deliveryRate,
        Double bounceRate,
        Double complaintRate) {

    /**
     * Compatibility count for older frontends, retained throughout the supported rollback window.
     * Removal requires a reviewed client cutover.
     * @return confirmed-delivered messages, excluding drafts and unconfirmed submissions
     * @deprecated use {@link #preparedRecipients()} for preparation or {@link #delivered()} for delivery
     */
    @Deprecated
    @JsonProperty("totalRecipients")
    public int totalRecipients() {
        return delivered;
    }
}
