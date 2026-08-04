package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One tenant-scoped, generation-pinned trigger delivery for a workflow. */
@Data
@NoArgsConstructor
public class WorkflowTriggerOutbox {
    private long id;
    private int workspaceId;
    private int workflowId;
    private long workflowVersionId;
    private long workflowRuntimeGeneration;
    private String triggerType;
    private String triggerEvent;
    private String triggerKey;
    private String recordType;
    private Integer recordId;
    private LocalDateTime occurredAt;
    private int recordScanAfterId;
    private int recordScanUpperId;
    private String dedupeKey;
    private String status;
    private LocalDateTime availableAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private int deliveryAttemptCount;
    private String lastErrorCode;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
