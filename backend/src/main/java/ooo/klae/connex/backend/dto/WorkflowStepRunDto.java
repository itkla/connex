package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

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
    Map<String, Object> actionOutputs,
    @JsonProperty("wait") Wait waitEvidence,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    Long durationMs,
    WorkflowRunSummaryDto.Failure failure
) {

    /** Correlated event-wait evidence retained with the original WAIT checkpoint. */
    @JsonInclude(Include.ALWAYS)
    public record Wait(
        String kind,
        String event,
        String sourceNodeId,
        String sourceOutput,
        int sourceTaskId,
        LocalDateTime timeoutAt,
        String resolution,
        Long matchedEventId,
        LocalDateTime resolvedAt
    ) { }

    public WorkflowStepRunDto(
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
            Map<String, Object> actionOutputs,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            Long durationMs,
            WorkflowRunSummaryDto.Failure failure) {
        this(
            sequence, nodeId, nodeType, status, attempts, retrySafety, selectedOutcome,
            selectedEdgeId, nextNodeId, actionOutcome, actionReferenceId, actionOutputs,
            null, startedAt, finishedAt, durationMs, failure);
    }

    public WorkflowStepRunDto(
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
            WorkflowRunSummaryDto.Failure failure) {
        this(
            sequence, nodeId, nodeType, status, attempts, retrySafety, selectedOutcome,
            selectedEdgeId, nextNodeId, actionOutcome, actionReferenceId, null, null, startedAt,
            finishedAt, durationMs, failure);
    }
}
