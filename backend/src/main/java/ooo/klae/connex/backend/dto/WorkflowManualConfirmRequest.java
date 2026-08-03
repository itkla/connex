package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Confirmation of one frozen manual workflow scope. */
public record WorkflowManualConfirmRequest(
    @NotBlank @Size(max = 64) String scopeToken,
    @NotBlank @Size(max = 64) String scopeHash
) { }
