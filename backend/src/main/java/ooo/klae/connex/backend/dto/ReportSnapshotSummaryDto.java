package ooo.klae.connex.backend.dto;

import java.time.LocalDate;

/**
 * Metadata-only snapshot row used by bounded snapshot lists.
 * @param id snapshot id
 * @param reportDefinitionId owning definition id
 * @param periodStart first included date
 * @param periodEnd last included date
 * @param generatedBy generator user id
 * @param generatedAt generation timestamp
 */
public record ReportSnapshotSummaryDto(
        int id,
        int reportDefinitionId,
        LocalDate periodStart,
        LocalDate periodEnd,
        Integer generatedBy,
        String generatedAt) {
}
