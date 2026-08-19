package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * Bounded workflow projection for a global-search result row.
 *
 * <p>Excludes the draft graph, canvas, active-version metadata, and latest-run health the full
 * {@link WorkflowListItemDto} carries; those require lateral joins and JSON inspection a search row
 * never renders. Archived workflows are excluded from the group, so there is no archive timestamp
 * to report.
 *
 * @param id the workflow id
 * @param name the workflow name
 * @param description the optional description
 * @param enabled whether the workflow is currently enabled
 * @param recordType the draft record type the workflow acts on
 * @param updatedAt when the workflow last changed
 */
public record WorkflowSummaryDto(
        int id,
        String name,
        String description,
        boolean enabled,
        String recordType,
        LocalDateTime updatedAt) {
}
