package ooo.klae.connex.backend.services;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * Computes deterministic report delivery times in a schedule's local timezone.
 * Java zone rules move nonexistent local times forward across DST gaps and select
 * the earlier offset during overlaps. Results are normalized to UTC for MySQL.
 */
final class ReportScheduleCalculator {
    private ReportScheduleCalculator() {
    }

    /**
     * Computes the first delivery strictly after {@code now}. Weekly delivery
     * uses the current local weekday; monthly and quarterly delivery begin at the
     * next calendar boundary.
     */
    static LocalDateTime initial(String cadence, String timezone, int hourOfDay, Instant now) {
        ZoneId zone = ZoneId.of(timezone);
        ZonedDateTime localNow = now.atZone(zone);
        ZonedDateTime candidate = switch (cadence) {
            case "weekly" -> atHour(localNow.toLocalDate(), zone, hourOfDay);
            case "monthly" -> atHour(
                    YearMonth.from(localNow).plusMonths(1).atDay(1), zone, hourOfDay);
            case "quarterly" -> atHour(nextQuarterStart(localNow.toLocalDate()), zone, hourOfDay);
            default -> throw invalidCadence(cadence);
        };
        if ("weekly".equals(cadence) && !candidate.toInstant().isAfter(now)) {
            candidate = atHour(candidate.toLocalDate().plusWeeks(1), zone, hourOfDay);
        }
        return utc(candidate);
    }

    /**
     * Advances a previously scheduled UTC occurrence to the first matching local
     * occurrence strictly after {@code now}, avoiding stale catch-up bursts.
     */
    static LocalDateTime next(
            String cadence,
            String timezone,
            int hourOfDay,
            LocalDateTime scheduledAtUtc,
            Instant now) {
        ZoneId zone = ZoneId.of(timezone);
        ZonedDateTime scheduledLocal = scheduledAtUtc.atZone(ZoneOffset.UTC).withZoneSameInstant(zone);
        ZonedDateTime candidate = advance(cadence, scheduledLocal, zone, hourOfDay);
        while (!candidate.toInstant().isAfter(now)) {
            candidate = advance(cadence, candidate, zone, hourOfDay);
        }
        return utc(candidate);
    }

    private static ZonedDateTime advance(
            String cadence,
            ZonedDateTime scheduledLocal,
            ZoneId zone,
            int hourOfDay) {
        return switch (cadence) {
            case "weekly" -> atHour(scheduledLocal.toLocalDate().plusWeeks(1), zone, hourOfDay);
            case "monthly" -> atHour(
                    YearMonth.from(scheduledLocal).plusMonths(1).atDay(1), zone, hourOfDay);
            case "quarterly" -> atHour(
                    YearMonth.from(scheduledLocal).plusMonths(3).atDay(1), zone, hourOfDay);
            default -> throw invalidCadence(cadence);
        };
    }

    private static LocalDate nextQuarterStart(LocalDate date) {
        int nextQuarterMonth = (date.getMonthValue() - 1) / 3 * 3 + 4;
        int year = date.getYear();
        if (nextQuarterMonth > 12) {
            nextQuarterMonth = 1;
            year++;
        }
        return LocalDate.of(year, nextQuarterMonth, 1);
    }

    private static ZonedDateTime atHour(LocalDate date, ZoneId zone, int hourOfDay) {
        return date.atTime(hourOfDay, 0).atZone(zone);
    }

    private static LocalDateTime utc(ZonedDateTime dateTime) {
        return LocalDateTime.ofInstant(dateTime.toInstant(), ZoneOffset.UTC);
    }

    private static BadRequestException invalidCadence(String cadence) {
        return new BadRequestException("Invalid report schedule cadence: " + cadence);
    }
}
