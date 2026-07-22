package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/** API representation of a mutable workflow draft and its active version pointer. */
public record WorkflowDto(
    int id,
    String name,
    String description,
    boolean enabled,
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
    LocalDateTime updatedAt
) { }
