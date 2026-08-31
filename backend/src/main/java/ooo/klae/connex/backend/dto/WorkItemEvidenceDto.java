package ooo.klae.connex.backend.dto;

import java.time.Instant;
import java.time.LocalDate;

/** One authoritative fact supporting a work-item projection. */
public record WorkItemEvidenceDto(
    WorkItemEvidenceCode code,
    WorkItemSource sourceType,
    int sourceId,
    Instant occurredAt,
    LocalDate date,
    String label
) {
}
