package ooo.klae.connex.backend.dto;

import java.time.LocalDate;

/**
 * Frozen generated report snapshot.
 * @param id snapshot id
 * @param reportDefinitionId owning definition id
 * @param periodStart first included date
 * @param periodEnd last included date
 * @param computedResult frozen generated document
 * @param generatedBy generator user id
 * @param generatedAt generation timestamp
 */
public record ReportSnapshotDto(
        int id,
        int reportDefinitionId,
        LocalDate periodStart,
        LocalDate periodEnd,
        ReportDocumentDto computedResult,
        Integer generatedBy,
        String generatedAt) {
}
