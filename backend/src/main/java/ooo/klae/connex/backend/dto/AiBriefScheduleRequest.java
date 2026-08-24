package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for setting one member's brief schedule.
 *
 * <p>The whole schedule is replaced on every write, so a client that renders the form renders the
 * complete contract and a partial update cannot leave a period enabled that the member believed they
 * had turned off.
 *
 * @param timeZone IANA zone the local hour and weekday are interpreted in
 * @param dailyEnabled whether a daily brief should be scheduled
 * @param dailyHour local hour, 0-23, the daily brief becomes due at
 * @param weeklyEnabled whether a weekly review should be scheduled
 * @param weeklyWeekday ISO weekday, 1 (Monday) to 7 (Sunday), the weekly review becomes due on
 * @param weeklyHour local hour, 0-23, the weekly review becomes due at
 */
public record AiBriefScheduleRequest(
        @NotBlank
        @Size(max = 64)
        String timeZone,
        boolean dailyEnabled,
        @Min(0)
        @Max(23)
        int dailyHour,
        boolean weeklyEnabled,
        @Min(1)
        @Max(7)
        int weeklyWeekday,
        @Min(0)
        @Max(23)
        int weeklyHour) {
}
