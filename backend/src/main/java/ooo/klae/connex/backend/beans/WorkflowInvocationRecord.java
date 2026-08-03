package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One frozen record identity and its honest preview and execution outcome. */
@Data
@NoArgsConstructor
public class WorkflowInvocationRecord {
    private int workspaceId;
    private long invocationId;
    private int ordinal;
    private int recordId;
    private String previewStatus;
    private String previewReasonCode;
    private String executionStatus;
    private String executionFailureCategory;
    private Long workflowRunId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
