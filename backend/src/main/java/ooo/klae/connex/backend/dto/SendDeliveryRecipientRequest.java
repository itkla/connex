package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** One external recipient requested for a commercial-document envelope. */
public record SendDeliveryRecipientRequest(
        @Positive Integer personId,
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Pattern(regexp = "signer|viewer") String role,
        @Positive Integer recipientOrder) {
}
