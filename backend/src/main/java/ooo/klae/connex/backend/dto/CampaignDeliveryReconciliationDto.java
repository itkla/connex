package ooo.klae.connex.backend.dto;

/**
 * The persisted terminal state of an operator-reconciled campaign delivery.
 * @param deliveryId the resolved delivery
 * @param status the confirmed terminal delivery status
 * @param reconciliationRequired whether the provider outcome still needs reconciliation
 * @param reasonCode bounded failure reason safe for a product surface, or null
 */
public record CampaignDeliveryReconciliationDto(
        int deliveryId,
        String status,
        boolean reconciliationRequired,
        String reasonCode) {
}
