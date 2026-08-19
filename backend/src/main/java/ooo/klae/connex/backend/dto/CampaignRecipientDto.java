package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * One recipient behind a campaign engagement count, linked to the contact record it reached.
 *
 * <p>{@code personId} is the same record id {@link CampaignAudienceMemberDto} carries, so a client
 * can navigate straight to the contact. {@code personLabel} is resolved through the same
 * archive-aware label read the audience preview uses and is null when the contact is no longer
 * visible.
 *
 * @param deliveryId the delivery id
 * @param sendId the send the delivery belongs to
 * @param channel the send's delivery channel
 * @param personId the contact record id, or null once the contact link was cleared
 * @param personLabel the contact's display name, or null when it is no longer resolvable
 * @param status the current delivery status
 * @param skipReason the ordered skip reason when the delivery was skipped
 * @param createdAt when the delivery was materialized
 * @param updatedAt when the delivery last changed
 */
public record CampaignRecipientDto(
        int deliveryId,
        int sendId,
        String channel,
        Integer personId,
        String personLabel,
        String status,
        String skipReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
