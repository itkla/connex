package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** A workspace-scoped canonical workflow traversal pinned to one immutable version. */
@Data
@NoArgsConstructor
public class WorkflowRun {
    private long id;
    private int workspaceId;
    private int workflowId;
    private long workflowVersionId;
    private String status;
    private String triggerType;
    private String triggerEvent;
    private String triggerKey;
    private String recordType;
    private int recordId;
    private String dedupeKey;
    private String executionMode;
    private Integer actorUserId;
    private Integer attributionUserId;
    private String currentNodeId;
    private String failureNodeId;
    private String failureCode;
    private String failureMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
}
