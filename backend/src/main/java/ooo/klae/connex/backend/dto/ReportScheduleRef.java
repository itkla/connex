package ooo.klae.connex.backend.dto;

/**
 * Catalog-local reference to a due report delivery schedule.
 * @param workspaceId owning workspace
 * @param scheduleId due schedule id
 */
public record ReportScheduleRef(int workspaceId, int scheduleId) {
}
