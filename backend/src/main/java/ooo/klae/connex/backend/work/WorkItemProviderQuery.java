package ooo.klae.connex.backend.work;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import ooo.klae.connex.backend.dto.WorkItemUrgency;

/** One immutable evaluation snapshot passed to every selected provider. */
public record WorkItemProviderQuery(
    int workspaceId,
    int actorId,
    LocalDate actorToday,
    Instant asOf,
    Set<WorkItemUrgency> urgencies,
    int candidateLimit
) {
}
