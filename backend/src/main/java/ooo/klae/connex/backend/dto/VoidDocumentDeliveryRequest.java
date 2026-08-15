package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Operator reason for voiding a live document-delivery envelope. */
public record VoidDocumentDeliveryRequest(
        @NotBlank @Size(max = 500) String reason) {
}
