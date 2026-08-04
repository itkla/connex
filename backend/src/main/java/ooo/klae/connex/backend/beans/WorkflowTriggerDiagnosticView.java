package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Unresolved fixed-code trigger delivery diagnostic without CRM record content. */
@Data
@NoArgsConstructor
public class WorkflowTriggerDiagnosticView {
    private long outboxId;
    private int workflowId;
    private String workflowName;
    private String triggerType;
    private String reasonCode;
    private LocalDateTime failedAt;
}
