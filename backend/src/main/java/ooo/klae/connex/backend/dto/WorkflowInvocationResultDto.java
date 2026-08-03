package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Honest aggregate and per-record outcome for one exact manual invocation. */
@JsonInclude(Include.ALWAYS)
public record WorkflowInvocationResultDto(
    long invocationId,
    String status,
    int exactCount,
    int queuedCount,
    int runningCount,
    int waitingCount,
    int succeededCount,
    int failedCount,
    int interventionRequiredCount,
    int cancelledCount,
    int skippedCount,
    LocalDateTime createdAt,
    LocalDateTime confirmedAt,
    LocalDateTime completedAt,
    List<RecordResult> records
) {

    /** One frozen record's current execution outcome. */
    public record RecordResult(
        int recordId,
        String status,
        String reasonCode,
        String runKey
    ) { }
}
