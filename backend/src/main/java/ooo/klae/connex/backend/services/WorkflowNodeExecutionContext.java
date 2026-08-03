package ooo.klae.connex.backend.services;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;

/** Revalidated state supplied to one canonical node execution. */
public record WorkflowNodeExecutionContext(
    WorkflowRun run,
    WorkflowVersion version,
    CompiledWorkflow compiled,
    WorkflowExecutionPrincipal principal
) { }
