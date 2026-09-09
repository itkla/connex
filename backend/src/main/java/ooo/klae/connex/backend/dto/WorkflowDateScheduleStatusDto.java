package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Current generation date reconciliation and occurrence backlog for one workflow. */
@JsonInclude(Include.ALWAYS)
public record WorkflowDateScheduleStatusDto(
    int plannedCount,
    int queuedCount,
    int missedCount,
    LocalDateTime nextDueAt,
    LocalDateTime lastReconciledAt
) { }
