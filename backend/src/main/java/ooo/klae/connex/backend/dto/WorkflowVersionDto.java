package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/** API representation of one immutable published workflow version. */
public record WorkflowVersionDto(
    long id,
    int versionNumber,
    String name,
    String description,
    String recordType,
    String executionMode,
    Integer runAsUserId,
    Integer createdById,
    Integer publishedById,
    WorkflowDefinition definition,
    WorkflowCanvas canvas,
    LocalDateTime publishedAt
) { }
