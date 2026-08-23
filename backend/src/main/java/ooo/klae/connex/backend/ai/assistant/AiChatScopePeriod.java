package ooo.klae.connex.backend.ai.assistant;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * One resolved date window and the stored-timestamp boundaries that express it.
 *
 * <p>The dates are the workspace's reporting calendar, so they read back as the same days the scope
 * echo states; the boundaries are the UTC instants those local days begin and end at, so the query
 * covers exactly the window the requester confirmed.
 *
 * @param start inclusive first local date, or null when the read is unbounded
 * @param end inclusive last local date, or null when the read is unbounded
 * @param startUtc inclusive lower bound on the stored timestamp, or null when unbounded
 * @param endUtc inclusive upper bound on the stored timestamp, or null when unbounded
 */
record AiChatScopePeriod(
        LocalDate start, LocalDate end, LocalDateTime startUtc, LocalDateTime endUtc) {

    /** The window of a read the caller placed no period on. */
    static final AiChatScopePeriod UNBOUNDED = new AiChatScopePeriod(null, null, null, null);

    /** @return whether the read is bounded to a period */
    boolean bounded() {
        return start != null && end != null;
    }

    /**
     * Returns the window a declared scope states, without a default.
     *
     * <p>A scope resolves both endpoints together or neither, so an absent endpoint means the caller
     * asked for no period at all and the read stays unbounded rather than inventing one.
     *
     * @param scope validated declared turn scope
     * @param workspaceService resolver for the workspace's reporting calendar
     * @return the declared window, or {@link #UNBOUNDED}
     */
    static AiChatScopePeriod of(AiChatQueryScope scope, WorkspaceService workspaceService) {
        if (scope.periodStart() == null || scope.periodEnd() == null) {
            return UNBOUNDED;
        }
        return between(
                scope.periodStart(),
                scope.periodEnd(),
                AiChatScopeCalendar.zone(workspaceService));
    }

    /** Returns the window two local dates cover in one reporting calendar. */
    static AiChatScopePeriod between(LocalDate start, LocalDate end, ZoneId zone) {
        return new AiChatScopePeriod(
                start,
                end,
                AiChatScopeCalendar.startUtc(start, zone),
                AiChatScopeCalendar.endUtcInclusive(end, zone));
    }
}
