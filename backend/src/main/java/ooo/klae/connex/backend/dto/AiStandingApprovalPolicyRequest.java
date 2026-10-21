package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Exact organization ceiling replacement; versions are positive decimal strings. */
public record AiStandingApprovalPolicyRequest(
        @NotNull Boolean enabled,
        @NotNull @Size(max = 4) List<@NotNull String> allowedTools,
        @NotNull @Min(1) @Max(10000) Integer maximumDailyCap,
        @NotNull @Min(1) @Max(10000) Integer maximumPerRunCap,
        @NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String expectedStateVersion) {
}
