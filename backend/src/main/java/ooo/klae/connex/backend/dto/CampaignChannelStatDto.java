package ooo.klae.connex.backend.dto;

/**
 * Per-channel delivery volume for one campaign.
 * @param channel the delivery channel
 * @param deliveries the number of materialized deliveries dispatched on that channel
 */
public record CampaignChannelStatDto(String channel, int deliveries) {
}
