package ooo.klae.connex.backend.dto;

import java.time.DayOfWeek;
import java.util.List;

/**
 * Global quiet-hours settings and their state at one UTC snapshot.
 * @param enabled whether disruptive delivery is suppressed during the window
 * @param timezone IANA timezone used for the recurring local window
 * @param start local inclusive start in HH:mm form
 * @param end local exclusive end in HH:mm form
 * @param days local days on which the interval starts
 * @param activeNow whether the snapshot falls in a configured interval
 * @param nextTransitionAt next UTC instant when the active state changes
 * @param bypassPolicy fixed bypass policy identifier
 */
public record NotificationQuietHoursDto(
        boolean enabled,
        String timezone,
        String start,
        String end,
        List<DayOfWeek> days,
        boolean activeNow,
        String nextTransitionAt,
        String bypassPolicy) {
}
