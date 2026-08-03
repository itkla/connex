package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Permission-safe summary shared by canonical and retained legacy workflow history. */
@JsonInclude(Include.ALWAYS)
public record WorkflowRunSummaryDto(
    String runKey,
    String source,
    String status,
    String legacyStatus,
    Version version,
    Trigger trigger,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    Long durationMs,
    Failure failure,
    boolean stepDetailAvailable
) {

    /** Immutable version evidence for a canonical run. */
    @JsonInclude(Include.ALWAYS)
    public record Version(
        long id,
        int number,
        String definitionHash,
        LocalDateTime publishedAt
    ) { }

    /** Bounded trigger evidence without record content. */
    @JsonInclude(Include.ALWAYS)
    public record Trigger(
        String type,
        String event,
        String recordType,
        Integer recordId
    ) { }

    /** Fixed-code failure evidence without exception text. */
    @JsonInclude(Include.ALWAYS)
    public record Failure(
        String nodeId,
        String code,
        String message
    ) { }
}
