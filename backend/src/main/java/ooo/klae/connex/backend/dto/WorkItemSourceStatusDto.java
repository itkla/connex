package ooo.klae.connex.backend.dto;

import java.time.Instant;

/** Truthful availability and known totals for one selected provider. */
public record WorkItemSourceStatusDto(
    WorkItemSource source,
    WorkItemSourceAvailability status,
    Long matchingTotal,
    Long overallTotal,
    Instant asOf,
    String errorCode
) {
}
