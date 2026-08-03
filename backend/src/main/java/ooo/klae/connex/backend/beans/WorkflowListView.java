package ooo.klae.connex.backend.beans;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Bounded workflow list row with active-version and latest-run candidates but no graph JSON. */
@Data
@NoArgsConstructor
public class WorkflowListView {
    private int id;
    private int workspaceId;
    private String name;
    private String description;
    private boolean enabled;
    private String runtimeOwner;
    private LocalDateTime archivedAt;
    private LocalDateTime intakePausedAt;
    private Integer intakePausedById;
    private int draftRevision;
    private String recordType;
    private String executionMode;
    private Integer runAsUserId;
    private Long activeVersionId;
    private Integer activeVersionNumber;
    private LocalDateTime activeVersionPublishedAt;
    private int nodeCount;
    private int actionCount;
    private Long canonicalRunId;
    private String canonicalRunStatus;
    private LocalDateTime canonicalRunStartedAt;
    private LocalDateTime canonicalRunFinishedAt;
    private Integer legacyRunId;
    private String legacyRunStatus;
    private LocalDateTime legacyRunExecutedAt;
    private Integer createdById;
    private Integer updatedById;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
