package ooo.klae.connex.backend.dto;

/** Workspace-level workflow operations counts. */
public record WorkflowOperationsSummaryDto(
    int workflowCount,
    int healthyCount,
    int pausedCount,
    int disabledCount,
    int interventionRequiredCount,
    int queuedCount,
    int waitingCount,
    int overdueCount,
    int openInterventionCount,
    int recentFailureCount
) { }
