package ooo.klae.connex.backend.exceptions;

import ooo.klae.connex.backend.dto.WorkflowDiagnosticDto;

/** A domain-invalid workflow finding carrying one stable structured diagnostic. */
public class WorkflowDefinitionValidationException extends BadRequestException {

    private final WorkflowDiagnosticDto diagnostic;

    public WorkflowDefinitionValidationException(
            String message, WorkflowDiagnosticDto diagnostic) {
        super(message);
        this.diagnostic = diagnostic;
    }

    public WorkflowDiagnosticDto diagnostic() {
        return diagnostic;
    }
}
