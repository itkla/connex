package ooo.klae.connex.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Create or replace payload for a report goal.
 * @param ownerId optional owner scope; null means workspace-wide
 * @param metric goal metric
 * @param periodType month or quarter
 * @param periodStart canonical first day of the period
 * @param targetValue non-negative revenue target
 * @param currency revenue currency
 */
public record ReportGoalRequest(
        @Positive Integer ownerId,
        @NotBlank @Pattern(regexp = "won_revenue") String metric,
        @NotBlank @Pattern(regexp = "month|quarter") String periodType,
        @NotNull LocalDate periodStart,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal targetValue,
        @NotBlank @Size(max = 8) @Pattern(regexp = "[A-Za-z]{3,8}") String currency) {
}
