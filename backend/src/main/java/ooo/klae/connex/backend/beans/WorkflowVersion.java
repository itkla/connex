package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An immutable published workflow version and its flattened runtime projection, scoped to the
 * owning workflow's workspace.
 */
@Data
@NoArgsConstructor
public class WorkflowVersion {
    private long id;
    private int workspaceId;
    private int workflowId;
    private int versionNumber;
    private String name;
    private String description;
    private String recordType;
    private String triggerType;
    private String triggerConfig;
    private String conditionJson;
    private String actionsJson;
    private String executionMode;
    private Integer runAsUserId;
    private Integer createdById;
    private Integer publishedById;
    private String definitionJson;
    private String canvasJson;
    private byte[] definitionHash;
    private LocalDateTime publishedAt;
}
