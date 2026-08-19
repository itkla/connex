package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * One campaign touch on a contact's record timeline: the campaign that reached them, on which
 * channel, and what became of that delivery.
 *
 * @param deliveryId the delivery id
 * @param campaignId the campaign that reached the contact
 * @param campaignName the campaign name
 * @param sendId the send the delivery belongs to
 * @param channel the send's delivery channel
 * @param status the current delivery status
 * @param skipReason the ordered skip reason when the delivery was skipped
 * @param createdAt when the delivery was materialized
 * @param updatedAt when the delivery last changed
 */
public record PersonCampaignTouchDto(
        int deliveryId,
        int campaignId,
        String campaignName,
        int sendId,
        String channel,
        String status,
        String skipReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
