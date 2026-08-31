package ooo.klae.connex.backend.dto;

import java.time.Instant;
import java.util.List;

/** Bounded My Work totals for count-only consumers. */
public record WorkItemSummaryDto(
    long knownTotal,
    long knownCritical,
    boolean totalsComplete,
    WorkItemAvailability availability,
    List<WorkItemSourceStatusDto> sourceStatuses,
    Instant asOf
) {
}
