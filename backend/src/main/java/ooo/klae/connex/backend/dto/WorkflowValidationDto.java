package ooo.klae.connex.backend.dto;

/** Successful read-only validation result for the current workflow draft revision. */
public record WorkflowValidationDto(int draftRevision, boolean valid) { }
