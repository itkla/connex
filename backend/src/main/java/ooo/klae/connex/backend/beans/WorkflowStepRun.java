package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One durable node checkpoint within a canonical workflow run. */
@Data
@NoArgsConstructor
public class WorkflowStepRun {
    private long id;
    private int workspaceId;
    private long workflowRunId;
    private int sequenceNumber;
    private String nodeId;
    private String nodeType;
    private String status;
    private int attemptCount;
    private String retrySafety;
    private String selectedOutcome;
    private String selectedEdgeId;
    private String nextNodeId;
    private String failureCode;
    private String failureMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
