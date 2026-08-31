package ooo.klae.connex.backend.dto;

import java.time.LocalDate;

/** Structured explanation for deterministic My Work inclusion. */
public record WorkItemReasonDto(
    WorkItemReasonCode code,
    LocalDate date,
    Integer days,
    String requestedByLabel
) {
}
