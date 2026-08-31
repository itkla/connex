package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Validated approval-step decision submitted from My Work. */
public record WorkItemDecisionRequest(
    @NotNull @Min(1) Integer stepId,
    @NotNull @Pattern(regexp = "approved|rejected") String decision,
    @Size(max = 1000) String comment
) {
}
