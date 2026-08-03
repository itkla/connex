package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Compare-and-swap precondition for a workflow runtime ownership transition. */
public record WorkflowRuntimeOwnerRequest(
    @NotNull @Positive Long expectedActiveVersionId
) { }
