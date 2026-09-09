package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

/** API representation of a mutable workflow draft and its active version pointer. */
public record WorkflowDto(
    int id,
    String name,
    String description,
    boolean enabled,
    String runtimeOwner,
    LocalDateTime archivedAt,
    LocalDateTime intakePausedAt,
    Integer intakePausedById,
    int draftRevision,
    String recordType,
    String executionMode,
    Integer runAsUserId,
    WorkflowDefinition definition,
    WorkflowCanvas canvas,
    Long activeVersionId,
    Integer createdById,
    Integer updatedById,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    @JsonInclude(JsonInclude.Include.NON_NULL) RuleTrigger trigger,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    WorkflowDateScheduleStatusDto dateScheduleStatus
) {

    public WorkflowDto(
            int id,
            String name,
            String description,
            boolean enabled,
            String runtimeOwner,
            LocalDateTime archivedAt,
            LocalDateTime intakePausedAt,
            Integer intakePausedById,
            int draftRevision,
            String recordType,
            String executionMode,
            Integer runAsUserId,
            WorkflowDefinition definition,
            WorkflowCanvas canvas,
            Long activeVersionId,
            Integer createdById,
            Integer updatedById,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        this(
            id, name, description, enabled, runtimeOwner, archivedAt, intakePausedAt,
            intakePausedById, draftRevision, recordType, executionMode, runAsUserId,
            definition, canvas, activeVersionId, createdById, updatedById, createdAt,
            updatedAt, null, null);
    }
}
