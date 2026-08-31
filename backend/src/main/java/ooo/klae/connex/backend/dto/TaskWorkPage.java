package ooo.klae.connex.backend.dto;

import java.time.Instant;
import java.util.List;

/** Bounded assigned-task projection and its exact source totals. */
public record TaskWorkPage(
    List<TaskWorkItem> items,
    long matchingTotal,
    long overallTotal,
    Instant asOf
) {
}
