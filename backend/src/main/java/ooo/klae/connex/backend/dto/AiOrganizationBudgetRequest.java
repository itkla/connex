package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Organization daily token limit, where zero disables budget enforcement. */
public record AiOrganizationBudgetRequest(
        @NotNull @Min(0) @Max(1_000_000_000_000L) Long dailyUsageLimit) {
}
