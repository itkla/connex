package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Exact-scope manual workflow preparation request. */
public record WorkflowManualPrepareRequest(
    @NotBlank @Size(max = 24) String sourceSurface,
    @NotNull WorkflowManualScope scope
) { }
