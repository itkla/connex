package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Durable correlation between one workflow WAIT node and task-completion evidence. */
@Data
@NoArgsConstructor
public class WorkflowEventWait {
    private long id;
    private int workspaceId;
    private long workflowRunId;
    private long workflowStepRunId;
    private long sourceStepRunId;
    private String nodeId;
    private int sourceTaskId;
    private String eventType;
    private LocalDateTime timeoutAt;
    private String resolution;
    private Long matchedEventId;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
}
