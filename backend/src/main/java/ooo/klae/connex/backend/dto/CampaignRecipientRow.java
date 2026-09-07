package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * One materialized campaign delivery as the recipient list reads it, before the contact label is
 * resolved. Carries no contact identity beyond the record id, so the mapper never has to join the
 * person table.
 *
 * @param deliveryId the delivery id
 * @param sendId the send the delivery belongs to
 * @param channel the send's delivery channel
 * @param personId the contact record id, or null once the contact link was cleared
 * @param status the current delivery status
 * @param skipReason the ordered skip reason when the delivery was skipped
 * @param reconciliationRequiredAt when provider-outcome reconciliation became required, or null
 * @param reasonCode bounded failure reason safe for a product surface, or null
 * @param createdAt when the delivery was materialized
 * @param updatedAt when the delivery last changed
 */
public record CampaignRecipientRow(
        int deliveryId,
        int sendId,
        String channel,
        Integer personId,
        String status,
        String skipReason,
        LocalDateTime reconciliationRequiredAt,
        String reasonCode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
