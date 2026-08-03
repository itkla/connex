package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Assignable operator intervention backed by authoritative workflow run state. */
@Data
@NoArgsConstructor
public class WorkflowIntervention {
    private long id;
    private int workspaceId;
    private long workflowRunId;
    private Long workflowStepRunId;
    private String stepNodeId;
    private byte[] interventionKey;
    private String category;
    private String reasonCode;
    private Integer ownerUserId;
    private String status;
    private int sourceVersion;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
