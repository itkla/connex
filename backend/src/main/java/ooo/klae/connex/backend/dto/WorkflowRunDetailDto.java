package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Full permission-safe run evidence for a canonical or retained legacy execution. */
@JsonInclude(Include.ALWAYS)
public record WorkflowRunDetailDto(
    String runKey,
    String source,
    int workflowId,
    String status,
    String legacyStatus,
    Version version,
    Execution execution,
    WorkflowRunSummaryDto.Trigger trigger,
    WorkflowRunSummaryDto.RuntimeState runtimeState,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    Long durationMs,
    WorkflowRunSummaryDto.Failure failure,
    boolean stepDetailAvailable,
    List<WorkflowStepRunDto> path
) {

    /** Immutable definition, canvas, and publication evidence for a canonical run. */
    @JsonInclude(Include.ALWAYS)
    public record Version(
        long id,
        int number,
        String definitionHash,
        LocalDateTime publishedAt,
        WorkflowDefinition definition,
        WorkflowCanvas canvas
    ) { }

    /** Actor and attribution identifiers captured when the run was claimed. */
    @JsonInclude(Include.ALWAYS)
    public record Execution(
        String mode,
        Integer actorUserId,
        Integer attributionUserId
    ) { }
}
