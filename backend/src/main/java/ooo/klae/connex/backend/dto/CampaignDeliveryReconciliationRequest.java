package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * An operator-confirmed resolution for a campaign delivery with an ambiguous provider outcome.
 * @param resolution {@code delivered} or {@code not_delivered}
 */
public record CampaignDeliveryReconciliationRequest(
        @NotBlank
        @Pattern(regexp = "delivered|not_delivered")
        String resolution) {
}
