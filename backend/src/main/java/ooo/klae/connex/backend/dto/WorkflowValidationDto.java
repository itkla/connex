package ooo.klae.connex.backend.dto;

import java.util.List;

/** Authoritative structured validation and publication readiness for one saved draft revision. */
public record WorkflowValidationDto(
    int draftRevision,
    boolean valid,
    boolean canPublish,
    boolean systemAuthoringAllowed,
    List<String> requiredPermissions,
    List<String> missingPermissions,
    List<WorkflowDiagnosticDto> errors
) {

    public WorkflowValidationDto {
        requiredPermissions = List.copyOf(requiredPermissions);
        missingPermissions = List.copyOf(missingPermissions);
        errors = List.copyOf(errors);
    }
}
