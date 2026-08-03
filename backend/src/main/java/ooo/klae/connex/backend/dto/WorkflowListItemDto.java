package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Bounded workflow-list projection that excludes definition and canvas payloads. */
@JsonInclude(Include.ALWAYS)
public record WorkflowListItemDto(
    int id,
    String name,
    String description,
    boolean enabled,
    String runtimeOwner,
    LocalDateTime archivedAt,
    int draftRevision,
    String recordType,
    String executionMode,
    Integer runAsUserId,
    ActiveVersion activeVersion,
    int nodeCount,
    int actionCount,
    LatestRun latestRun,
    Integer createdById,
    Integer updatedById,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

    /** Immutable active-version metadata without graph payloads. */
    @JsonInclude(Include.ALWAYS)
    public record ActiveVersion(
        long id,
        int number,
        LocalDateTime publishedAt
    ) { }

    /** Latest canonical-or-legacy run health without record content or step diagnostics. */
    @JsonInclude(Include.ALWAYS)
    public record LatestRun(
        String runKey,
        String source,
        String status,
        String legacyStatus,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        boolean stepDetailAvailable
    ) { }
}
