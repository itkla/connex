package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Typed-name decision body for accepting a delivered commercial document. The flow identity echoes
 * the preview the signer actually read, so a grant swapped by another tab cannot decide it.
 */
public record AcceptDocumentRequest(
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String flowId,
        @NotBlank @Size(max = 255) String typedName) {
}
