package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Optimistic intervention ownership update. */
public record WorkflowInterventionOwnerRequest(
    @Min(1) Integer ownerUserId,
    @NotNull @Min(0) Integer expectedSourceVersion
) { }
