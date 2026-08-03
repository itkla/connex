package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Bounded durable evidence for one workflow action attempt. */
@Data
@NoArgsConstructor
public class WorkflowStepAttempt {
    private long id;
    private int workspaceId;
    private long workflowRunId;
    private long workflowStepRunId;
    private int attemptNumber;
    private String retrySafety;
    private String status;
    private String errorCode;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
