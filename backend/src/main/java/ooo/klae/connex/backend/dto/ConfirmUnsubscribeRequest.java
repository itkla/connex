package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Confirmation body for a campaign unsubscribe. The flow identity echoes the preview the recipient
 * actually read, so a grant swapped by another tab cannot suppress a different address.
 */
public record ConfirmUnsubscribeRequest(
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String flowId) {
}
