package ooo.klae.connex.backend.dto;

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
}
