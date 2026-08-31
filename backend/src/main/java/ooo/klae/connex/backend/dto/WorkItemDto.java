package ooo.klae.connex.backend.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** One ranked projection over an authoritative source record. */
public record WorkItemDto(
    String id,
    WorkItemSource source,
    int sourceId,
    String title,
    WorkItemReasonDto reason,
    LocalDate dueDate,
    WorkItemUrgency urgency,
    List<WorkItemEvidenceDto> evidence,
    Instant freshnessAt,
    Instant asOf,
    String currentVersion,
    String etag,
    WorkItemContextDto context,
    List<WorkItemAction> permittedActions
) {
}
