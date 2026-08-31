package ooo.klae.connex.backend.dto;

import java.time.Instant;
import java.util.List;

/** Bounded deal-close projection and its exact source totals. */
public record NotificationWorkPage(
    List<NotificationWorkItem> items,
    long matchingTotal,
    long overallTotal,
    Instant asOf
) {
}
