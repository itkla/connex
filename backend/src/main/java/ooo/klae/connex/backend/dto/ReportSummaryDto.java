package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * Bounded report-definition projection for a global-search result row.
 *
 * <p>Excludes the authored builder configuration the full {@link ReportDefinitionDto} carries: a
 * search row never renders it, and parsing every matched definition's JSON on each keystroke would
 * be wasted work.
 *
 * @param id the report definition id
 * @param name the report name
 * @param description the optional description
 * @param cadence the cadence key
 * @param updatedAt when the definition last changed
 */
public record ReportSummaryDto(
        int id,
        String name,
        String description,
        String cadence,
        LocalDateTime updatedAt) {
}
