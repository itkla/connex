package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class ReportScheduleCalculatorTest {

    @Test
    void weeklyUsesCurrentLocalWeekdayAndSelectedHour() {
        LocalDateTime result = ReportScheduleCalculator.initial(
                "weekly", "America/New_York", 9, Instant.parse("2026-07-13T12:00:00Z"));

        assertEquals(LocalDateTime.of(2026, 7, 13, 13, 0), result);
    }

    @Test
    void weeklyMovesToNextWeekWhenTodaysHourPassed() {
        LocalDateTime result = ReportScheduleCalculator.initial(
                "weekly", "America/New_York", 9, Instant.parse("2026-07-13T14:00:00Z"));

        assertEquals(LocalDateTime.of(2026, 7, 20, 13, 0), result);
    }

    @Test
    void monthlyStartsAtNextLocalMonthBoundary() {
        LocalDateTime result = ReportScheduleCalculator.initial(
                "monthly", "Asia/Tokyo", 8, Instant.parse("2026-07-31T23:30:00Z"));

        assertEquals(LocalDateTime.of(2026, 8, 31, 23, 0), result);
    }

    @Test
    void quarterlyStartsAtNextCalendarQuarter() {
        LocalDateTime result = ReportScheduleCalculator.initial(
                "quarterly", "Pacific/Honolulu", 6, Instant.parse("2026-07-13T12:00:00Z"));

        assertEquals(LocalDateTime.of(2026, 10, 1, 16, 0), result);
    }

    @Test
    void dstGapMovesNonexistentHourForward() {
        LocalDateTime result = ReportScheduleCalculator.next(
                "weekly",
                "America/New_York",
                2,
                LocalDateTime.of(2026, 3, 1, 7, 0),
                Instant.parse("2026-03-01T08:00:00Z"));

        assertEquals(LocalDateTime.of(2026, 3, 8, 7, 0), result);
    }

    @Test
    void overdueOccurrencesAdvanceToFirstFutureCadence() {
        LocalDateTime result = ReportScheduleCalculator.next(
                "monthly",
                "UTC",
                9,
                LocalDateTime.of(2026, 1, 1, 9, 0),
                Instant.parse("2026-04-15T12:00:00Z"));

        assertEquals(LocalDateTime.of(2026, 5, 1, 9, 0), result);
    }
}
