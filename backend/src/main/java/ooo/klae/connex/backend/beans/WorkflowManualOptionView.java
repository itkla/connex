package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One published workflow candidate for manual-entry discovery. */
@Data
@NoArgsConstructor
public class WorkflowManualOptionView {
    private int workflowId;
    private String workflowName;
    private boolean enabled;
    private String runtimeOwner;
    private LocalDateTime archivedAt;
    private LocalDateTime intakePausedAt;
    private long workflowVersionId;
    private int versionNumber;
    private String recordType;
    private String executionMode;
    private Integer runAsUserId;
    private Integer createdById;
    private String definitionJson;
    private byte[] definitionHash;
}
