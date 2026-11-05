package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Per-channel preparation, dispatch evidence, and receipt-backed delivery outcomes.
 * @param channel the delivery channel
 * @param preparedRecipients materialized recipients, including drafts and repeats across sends
 * @param dispatched confirmed submissions, regardless of current delivery status
 * @param delivered deliveries currently confirmed delivered by provider receipts
 * @param deliveryReceiptsAvailable whether this channel has any delivery, bounce, or complaint receipts
 */
public record CampaignChannelStatDto(
        String channel,
        int preparedRecipients,
        int dispatched,
        int delivered,
        boolean deliveryReceiptsAvailable) {

    /**
     * Compatibility count for older frontends that labeled this field as delivered.
     * Retained throughout the supported rollback window; removal requires a reviewed client cutover.
     * @return confirmed-delivered messages, never prepared or merely submitted messages
     * @deprecated use {@link #delivered()} and {@link #deliveryReceiptsAvailable()} instead
     */
    @Deprecated
    @JsonProperty("deliveries")
    public int deliveries() {
        return delivered;
    }
}
