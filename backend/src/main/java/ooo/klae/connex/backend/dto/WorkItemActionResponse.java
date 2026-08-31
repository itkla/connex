package ooo.klae.connex.backend.dto;

import java.time.Instant;

/** Result of one source-owned My Work mutation. */
public record WorkItemActionResponse(
    WorkItemSource source,
    int sourceId,
    WorkItemActionOutcome outcome,
    boolean removedFromQueue,
    Long notificationStateVersion,
    Instant reconciledAt
) {
}
