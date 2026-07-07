package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Engine-evaluation opt-out for a deal (issue #358): {@code riskExcluded} removes the deal from
 * deal-risk assessment and its notifications. Plain close-date reminders are unaffected.
 */
@Data
@NoArgsConstructor
public class DealEvaluationDto {
    @NotNull
    private Boolean riskExcluded;
}
