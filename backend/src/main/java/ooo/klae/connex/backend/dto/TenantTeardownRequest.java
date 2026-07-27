package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Case-sensitive tenant slug confirmation for a destructive lifecycle request. */
public record TenantTeardownRequest(
        @NotBlank
        @Size(max = 255)
        String confirmation) {
}
