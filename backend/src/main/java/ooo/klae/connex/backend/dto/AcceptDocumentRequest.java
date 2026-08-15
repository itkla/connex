package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Typed-name decision body for accepting a delivered commercial document. */
public record AcceptDocumentRequest(
        @NotBlank @Size(max = 255) String typedName) {
}
