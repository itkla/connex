package ooo.klae.connex.backend.beans;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One durable source-date period for a date-triggered workflow and deal. */
@Data
@NoArgsConstructor
public class WorkflowDateEnrollment {
    private long id;
    private int workspaceId;
    private int workflowId;
    private long workflowVersionId;
    private long workflowRuntimeGeneration;
    private String recordType;
    private int recordId;
    private String dateField;
    private LocalDate sourceDate;
    private LocalDate scheduledLocalDate;
    private LocalDateTime dueAt;
    private String state;
    private Long workflowRunId;
    private LocalDateTime queuedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
