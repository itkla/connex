package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Complete replacement for one workspace's AI governance settings. */
public record AiWorkspaceGovernanceRequest(
        @NotNull Boolean enabled,
        @NotNull @Min(1) @Max(12) Integer assistantMaxSteps) {
}
