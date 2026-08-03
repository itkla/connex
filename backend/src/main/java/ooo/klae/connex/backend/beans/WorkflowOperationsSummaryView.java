package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Workspace-level operations counts computed in one bounded aggregate query. */
@Data
@NoArgsConstructor
public class WorkflowOperationsSummaryView {
    private int workflowCount;
    private int healthyCount;
    private int pausedCount;
    private int disabledCount;
    private int interventionRequiredCount;
    private int queuedCount;
    private int waitingCount;
    private int overdueCount;
    private int openInterventionCount;
    private int recentFailureCount;
}
