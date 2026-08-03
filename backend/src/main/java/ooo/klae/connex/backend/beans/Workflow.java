package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A workspace-scoped workflow with a mutable draft and an optional immutable active version.
 * Definition and canvas JSON are stored in canonical compact form.
 */
@Data
@NoArgsConstructor
public class Workflow {
    private int id;
    private int workspaceId;
    private Integer legacyRuleId;
    private String name;
    private String description;
    private boolean enabled;
    private String runtimeOwner = "legacy";
    private LocalDateTime archivedAt;
    private long runtimeGeneration;
    private int draftRevision;
    private String draftRecordType;
    private String draftExecutionMode;
    private Integer draftRunAsUserId;
    private String draftDefinitionJson;
    private String draftCanvasJson;
    private Long activeVersionId;
    private Integer createdById;
    private Integer updatedById;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
