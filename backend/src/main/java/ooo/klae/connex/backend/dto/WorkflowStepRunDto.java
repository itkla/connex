package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** One node outcome in the deterministic path taken by a canonical run. */
@JsonInclude(Include.ALWAYS)
public record WorkflowStepRunDto(
    int sequence,
    String nodeId,
    String nodeType,
    String status,
    int attempts,
    String retrySafety,
    String selectedOutcome,
    String selectedEdgeId,
    String nextNodeId,
    String actionOutcome,
    Long actionReferenceId,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    Long durationMs,
    WorkflowRunSummaryDto.Failure failure
) { }
