package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * An operator-confirmed resolution for an audience export whose provider outcome was ambiguous.
 * @param resolution {@code delivered} or {@code not_delivered}
 */
public record CampaignAudienceExportReconciliationRequest(
        @NotBlank
        @Pattern(regexp = "delivered|not_delivered")
        String resolution) {
}
