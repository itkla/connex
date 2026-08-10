package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Bounded deal values to check before creation.
 *
 * @param name proposed deal name
 * @param companyId proposed company association, or {@code null}
 * @param reviewToken acknowledged proof to validate without replacing, or {@code null}
 */
public record DealDuplicatePreflightRequest(
        @NotBlank @Size(max = 255) String name,
        @Positive Integer companyId,
        @Pattern(regexp = "^[0-9a-f]{64}$") String reviewToken) {
}
