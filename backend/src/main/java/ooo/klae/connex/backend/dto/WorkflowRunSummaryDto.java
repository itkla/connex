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
    String statusReason,
    DateSchedule dateSchedule,
    String legacyStatus,
    Version version,
    Trigger trigger,
    RuntimeState runtimeState,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    Long durationMs,
    Failure failure,
    boolean stepDetailAvailable
) {

    public WorkflowRunSummaryDto(
            String runKey,
            String source,
            String status,
            String legacyStatus,
            Version version,
            Trigger trigger,
            RuntimeState runtimeState,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            Long durationMs,
            Failure failure,
            boolean stepDetailAvailable) {
        this(
            runKey, source, status, null, null, legacyStatus, version, trigger,
            runtimeState, startedAt, finishedAt, durationMs, failure, stepDetailAvailable);
    }

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

    /** Bounded wait and cooperative-cancellation state for a canonical run. */
    @JsonInclude(Include.ALWAYS)
    public record RuntimeState(
        String waitKind,
        LocalDateTime resumeAt,
        boolean cancellationRequested
    ) { }

    /** Date-trigger source and due-time evidence captured on one run. */
    @JsonInclude(Include.ALWAYS)
    public record DateSchedule(
        String dateField,
        java.time.LocalDate sourceDate,
        java.time.LocalDate scheduledLocalDate,
        LocalDateTime dueAt
    ) { }

    /** Fixed-code failure evidence without exception text. */
    @JsonInclude(Include.ALWAYS)
    public record Failure(
        String nodeId,
        String code,
        String message
    ) { }
}
