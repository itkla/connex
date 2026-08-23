package ooo.klae.connex.backend.ai.assistant;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * The calendar a declared assistant scope's dates are read in.
 *
 * <p>Scope periods are the same kind of user-facing date window as a report period or an analytics
 * range, and those resolve against the workspace's reporting timezone. Resolving them in UTC instead
 * would make an America/Los_Angeles one-day scope cover part of the next local day and omit the
 * prior local evening, while the chip the requester confirmed still named a single local date. Both
 * the request-thread admission and the generation-thread retrieval resolve through here so the
 * echoed dates and the executed boundaries describe the same days.
 */
final class AiChatScopeCalendar {

    private AiChatScopeCalendar() {
    }

    /**
     * Returns the workspace's reporting calendar.
     *
     * @param workspaceService resolver for the active workspace's analytics timezone
     * @return the reporting zone, or UTC when the workspace states no resolvable calendar
     */
    static ZoneId zone(WorkspaceService workspaceService) {
        String timezone = workspaceService.getCurrentAnalyticsTimezone();
        if (timezone == null || timezone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException exception) {
            return ZoneOffset.UTC;
        }
    }

    /** Returns the UTC instant the local day starts at, as the stored timestamps express it. */
    static LocalDateTime startUtc(LocalDate localDate, ZoneId zone) {
        return LocalDateTime.ofInstant(
                localDate.atStartOfDay(zone).toInstant(), ZoneOffset.UTC);
    }

    /**
     * Returns the last UTC instant the local day covers.
     *
     * <p>Inclusive rather than exclusive because the cohort activity query bounds its window with
     * {@code <=}; the boundary is derived from the following local midnight so a day that gains or
     * loses an hour to a daylight-saving transition still ends exactly where the calendar says.
     */
    static LocalDateTime endUtcInclusive(LocalDate localDate, ZoneId zone) {
        return LocalDateTime.ofInstant(
                localDate.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1),
                ZoneOffset.UTC);
    }
}
