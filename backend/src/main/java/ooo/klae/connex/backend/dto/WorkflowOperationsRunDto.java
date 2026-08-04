package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Canonical run enriched for the operations queue. */
@JsonInclude(Include.ALWAYS)
public record WorkflowOperationsRunDto(
    int workflowId,
    String workflowName,
    String recipeKey,
    WorkflowRunSummaryDto run,
    String failureCategory,
    WorkflowInterventionDto intervention
) { }
