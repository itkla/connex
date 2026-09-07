package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Reason body for declining a delivered commercial document. The flow identity echoes the preview
 * the signer actually read, so a grant swapped by another tab cannot decide it.
 */
public record DeclineDocumentRequest(
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String flowId,
        @NotBlank @Size(max = 500) String reason) {
}
