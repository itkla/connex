package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import ooo.klae.connex.backend.beans.WorkflowTriggerDiagnosticView;

/** Support-safe unresolved trigger delivery diagnostic shown in workflow operations. */
public record WorkflowTriggerDiagnosticDto(
    long outboxId,
    int workflowId,
    String workflowName,
    String triggerType,
    String reasonCode,
    LocalDateTime failedAt
) {

    /** Creates the stable operations projection without trigger record content. */
    public static WorkflowTriggerDiagnosticDto from(WorkflowTriggerDiagnosticView view) {
        return new WorkflowTriggerDiagnosticDto(
            view.getOutboxId(),
            view.getWorkflowId(),
            view.getWorkflowName(),
            view.getTriggerType(),
            view.getReasonCode(),
            view.getFailedAt());
    }
}
