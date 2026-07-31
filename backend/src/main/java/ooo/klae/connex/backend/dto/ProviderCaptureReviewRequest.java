package ooo.klae.connex.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Optimistic decision for one held provider participant.
 */
public record ProviderCaptureReviewRequest(
    @NotNull @Pattern(regexp = "attach|create|ignore") String action,
    @Min(1) long version,
    boolean rememberExact,
    @Min(1) Integer personId,
    @Valid PersonDto contact,
    @Size(max = 2048) String duplicateReviewToken
) {
}
