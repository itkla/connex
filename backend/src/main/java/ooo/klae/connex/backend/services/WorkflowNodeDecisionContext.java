package ooo.klae.connex.backend.services;

import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;

/** Read-only state used for deterministic node transition selection. */
public record WorkflowNodeDecisionContext(
    int workspaceId,
    int attributionUserId,
    String triggerType,
    String recordType,
    int recordId,
    boolean scheduleEnrollmentConfirmed,
    CompiledWorkflow compiled
) { }
