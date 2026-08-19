package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * One campaign touch on a contact's record timeline: the campaign that reached them, on which
 * channel, and what became of that delivery.
 *
 * <p>The delivery's skip reason is deliberately absent: it names the consent or restriction ground
 * a send was withheld on, which stays with the recipient roster behind
 * {@code Permission.CONSENT_MANAGE}. A withheld touch still reports the {@code skipped} status.
 *
 * @param deliveryId the delivery id
 * @param campaignId the campaign that reached the contact
 * @param campaignName the campaign name
 * @param sendId the send the delivery belongs to
 * @param channel the send's delivery channel
 * @param status the current delivery status
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
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
