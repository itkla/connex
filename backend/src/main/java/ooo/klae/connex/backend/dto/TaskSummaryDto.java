package ooo.klae.connex.backend.dto;

/**
 * Workspace-wide task status and due-date counts.
 */
public record TaskSummaryDto(
    long todo,
    long inProgress,
    long done,
    long overdue,
    long dueSoon
) {}
