package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import ooo.klae.connex.backend.beans.AiBriefSchedule;

/**
 * One member's brief schedule as the command centre states it back to them.
 *
 * <p>Inclusion is pinned to ALWAYS because the client distinguishes "never delivered" from "delivery
 * time unknown", and the application-wide {@code non_null} inclusion would otherwise erase that
 * difference for every nullable timestamp here.
 *
 * @param timeZone IANA zone the local hour and weekday are interpreted in
 * @param dailyEnabled whether a daily brief is scheduled
 * @param dailyHour local hour, 0-23, the daily brief becomes due at
 * @param weeklyEnabled whether a weekly review is scheduled
 * @param weeklyWeekday ISO weekday, 1 (Monday) to 7 (Sunday), the weekly review becomes due on
 * @param weeklyHour local hour, 0-23, the weekly review becomes due at
 * @param pendingKind the period currently generating, or null when nothing is in flight
 * @param lastDeliveredAt when a brief was last delivered, or null
 * @param lastFailureAt when a scheduled run last failed, or null
 * @param lastFailureReason stable reason the last scheduled run failed, or null
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiBriefScheduleDto(
        String timeZone,
        boolean dailyEnabled,
        int dailyHour,
        boolean weeklyEnabled,
        int weeklyWeekday,
        int weeklyHour,
        String pendingKind,
        String lastDeliveredAt,
        String lastFailureAt,
        String lastFailureReason) {

    /** Projects one durable schedule, or the unscheduled default when the member has none. */
    public static AiBriefScheduleDto from(AiBriefSchedule schedule, String defaultTimeZone) {
        if (schedule == null) {
            return new AiBriefScheduleDto(
                    defaultTimeZone, false, 8, false, 1, 8, null, null, null, null);
        }
        return new AiBriefScheduleDto(
                schedule.getTimeZone(),
                schedule.isDailyEnabled(),
                schedule.getDailyHour(),
                schedule.isWeeklyEnabled(),
                schedule.getWeeklyWeekday(),
                schedule.getWeeklyHour(),
                schedule.getPendingKind(),
                schedule.getLastDeliveredAt(),
                schedule.getLastFailureAt(),
                schedule.getLastFailureReason());
    }
}
