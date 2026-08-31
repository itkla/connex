package ooo.klae.connex.backend.work;

import java.time.Instant;
import java.util.List;

import ooo.klae.connex.backend.dto.WorkItemDto;

/** Bounded provider result with known totals and truncation honesty. */
public record WorkItemProviderResult(
    List<WorkItemDto> items,
    long matchingTotal,
    long overallTotal,
    Instant asOf,
    boolean totalsComplete
) {
    /** Creates an exact provider result. */
    public WorkItemProviderResult(
            List<WorkItemDto> items,
            long matchingTotal,
            long overallTotal,
            Instant asOf) {
        this(items, matchingTotal, overallTotal, asOf, true);
    }
}
