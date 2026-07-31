package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Min;

/**
 * Optimistic admission approval for one captured interaction.
 */
public record ProviderCaptureApprovalRequest(@Min(1) long version) {
}
