package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Reason body for declining a delivered commercial document. */
public record DeclineDocumentRequest(
        @NotBlank @Size(max = 1000) String reason) {
}
