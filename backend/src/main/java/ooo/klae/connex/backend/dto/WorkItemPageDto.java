package ooo.klae.connex.backend.dto;

import java.time.Instant;
import java.util.List;

/** Ranked My Work page with provider-level failure honesty. */
public record WorkItemPageDto(
    List<WorkItemDto> items,
    int page,
    int size,
    long knownMatchingTotal,
    long knownOverallTotal,
    boolean totalsComplete,
    boolean hasNext,
    boolean hasNextKnown,
    WorkItemAvailability availability,
    List<WorkItemSourceStatusDto> sourceStatuses,
    Instant asOf
) {
}
