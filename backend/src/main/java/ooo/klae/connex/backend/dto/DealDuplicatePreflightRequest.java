package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Bounded deal values to check before creation.
 *
 * @param name proposed deal name
 * @param companyId proposed company association, or {@code null}
 */
public record DealDuplicatePreflightRequest(
        @NotBlank @Size(max = 255) String name,
        @Positive Integer companyId) {
}
