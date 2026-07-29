package ooo.klae.connex.backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.util.AnalyticsPeriods.AnalyticsPeriod;
import ooo.klae.connex.backend.util.AnalyticsPeriods.Window;

class AnalyticsPeriodsTest {
    @Test
    void alignsWeeksToMondayAndKeepsFullPartialEdgeBoundaries() {
        Window window = AnalyticsPeriods.requiredWindow(
            "2026-07-08", "2026-07-15", "UTC", null);

        List<AnalyticsPeriod> periods = AnalyticsPeriods.periods(window, "week");

        assertEquals(LocalDate.of(2026, 7, 6), periods.getFirst().startDate());
        assertEquals(LocalDate.of(2026, 7, 13), periods.getFirst().endDate());
        assertEquals(LocalDate.of(2026, 7, 13), periods.getLast().startDate());
        assertEquals(LocalDate.of(2026, 7, 20), periods.getLast().endDate());
        assertEquals(LocalDateTime.of(2026, 7, 8, 0, 0), window.startUtc());
        assertEquals(LocalDateTime.of(2026, 7, 16, 0, 0), window.endUtc());
    }

    @Test
    void alignsMonthsToTheFirstAndOrdersEveryBucket() {
        Window window = AnalyticsPeriods.requiredWindow(
            "2026-01-31", "2026-03-01", "UTC", null);

        List<AnalyticsPeriod> periods = AnalyticsPeriods.periods(window, "month");

        assertEquals(
            List.of(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 3, 1)),
            periods.stream().map(AnalyticsPeriod::startDate).toList());
        assertEquals(List.of(0, 1, 2), periods.stream().map(AnalyticsPeriod::index).toList());
    }

    @Test
    void computesAnImmediatelyPrecedingEqualLocalDayWindow() {
        Window window = AnalyticsPeriods.requiredWindow(
            "2026-05-10", "2026-05-12", "UTC", null);

        assertEquals(LocalDateTime.of(2026, 5, 7, 0, 0), window.previousStartUtc());
        assertEquals(LocalDateTime.of(2026, 5, 10, 0, 0), window.previousEndUtc());
    }

    @Test
    void convertsTokyoCalendarMidnightToUtc() {
        Window window = AnalyticsPeriods.requiredWindow(
            "2026-01-02", "2026-01-02", "Asia/Tokyo", null);

        assertEquals(LocalDateTime.of(2026, 1, 1, 15, 0), window.startUtc());
        assertEquals(LocalDateTime.of(2026, 1, 2, 15, 0), window.endUtc());
    }

    @Test
    void appliesNewYorkDaylightSavingRulesAtEachBoundary() {
        Window window = AnalyticsPeriods.requiredWindow(
            "2026-03-07", "2026-03-09", "America/New_York", null);
        List<AnalyticsPeriod> periods = AnalyticsPeriods.periods(window, "day");

        assertEquals(LocalDateTime.of(2026, 3, 8, 5, 0), periods.get(1).startUtc());
        assertEquals(LocalDateTime.of(2026, 3, 9, 4, 0), periods.get(1).endUtc());
        assertEquals(Duration.ofHours(23),
            Duration.between(periods.get(1).startUtc(), periods.get(1).endUtc()));
        assertEquals(Duration.ofHours(71), Duration.between(window.startUtc(), window.endUtc()));
        assertEquals(Duration.ofHours(72),
            Duration.between(window.previousStartUtc(), window.previousEndUtc()));
    }

    @Test
    void permits731InclusiveDaysAndRejectsLongerWindows() {
        Window allowed = AnalyticsPeriods.requiredWindow(
            "2024-01-01", "2025-12-31", "UTC", null);

        assertEquals(LocalDate.of(2026, 1, 1), allowed.endDate());
        assertThrows(BadRequestException.class,
            () -> AnalyticsPeriods.requiredWindow(
                "2024-01-01", "2026-01-01", "UTC", null));
    }

    @Test
    void rejectsMoreThan120AlignedBuckets() {
        Window window = AnalyticsPeriods.requiredWindow(
            "2026-01-01", "2026-05-01", "UTC", null);

        assertThrows(BadRequestException.class,
            () -> AnalyticsPeriods.periods(window, "day"));
    }

    @Test
    void ignoresWindowOnlyParametersWhenDatesAreAbsent() {
        assertTrue(AnalyticsPeriods.optionalWindow(
            null, null, "Mars/Olympus", "+09:00").isEmpty());
    }
}
