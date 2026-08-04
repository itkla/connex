package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Optimistic workflow intervention resolution request. */
public record WorkflowInterventionResolveRequest(
    @NotNull @Min(0) Integer expectedSourceVersion
) { }
