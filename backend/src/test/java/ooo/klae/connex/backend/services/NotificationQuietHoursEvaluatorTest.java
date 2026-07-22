package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.NotificationQuietHours;

class NotificationQuietHoursEvaluatorTest {
    private final NotificationQuietHoursEvaluator evaluator = new NotificationQuietHoursEvaluator();

    @Test
    void disabledConfigurationHasNoTransition() {
        NotificationQuietHours quietHours = quiet(false, "UTC", "09:00", "17:00", DayOfWeek.MONDAY);

        NotificationQuietHoursEvaluator.Evaluation result = evaluator.evaluate(
            quietHours, Instant.parse("2026-07-20T10:00:00Z"));

        assertFalse(result.active());
        assertNull(result.nextTransitionAt());
    }

    @Test
    void daytimeWindowUsesHalfOpenBounds() {
        NotificationQuietHours quietHours = quiet(true, "UTC", "09:00", "17:00", DayOfWeek.MONDAY);

        assertTrue(evaluator.evaluate(
            quietHours, Instant.parse("2026-07-20T09:00:00Z")).active());
        assertFalse(evaluator.evaluate(
            quietHours, Instant.parse("2026-07-20T17:00:00Z")).active());
        assertEquals(
            Instant.parse("2026-07-20T17:00:00Z"),
            evaluator.evaluate(quietHours, Instant.parse("2026-07-20T10:00:00Z")).nextTransitionAt());
    }

    @Test
    void overnightEarlyMorningUsesThePreviousStartDay() {
        NotificationQuietHours quietHours = quiet(true, "UTC", "22:00", "07:00", DayOfWeek.MONDAY);

        assertTrue(evaluator.evaluate(
            quietHours, Instant.parse("2026-07-21T06:00:00Z")).active());
        assertFalse(evaluator.evaluate(
            quietHours, Instant.parse("2026-07-21T07:00:00Z")).active());
        assertEquals(
            Instant.parse("2026-07-27T22:00:00Z"),
            evaluator.evaluate(quietHours, Instant.parse("2026-07-21T08:00:00Z")).nextTransitionAt());
    }

    @Test
    void springGapMovesTheStartForward() {
        NotificationQuietHours quietHours = quiet(
            true, "America/New_York", "02:30", "04:00", DayOfWeek.SUNDAY);

        assertFalse(evaluator.evaluate(
            quietHours, Instant.parse("2026-03-08T07:29:59Z")).active());
        NotificationQuietHoursEvaluator.Evaluation active = evaluator.evaluate(
            quietHours, Instant.parse("2026-03-08T07:30:00Z"));
        assertTrue(active.active());
        assertEquals(Instant.parse("2026-03-08T08:00:00Z"), active.nextTransitionAt());
    }

    @Test
    void fallOverlapUsesTheEarlierStartAndStaysActiveThroughRepeatedTime() {
        NotificationQuietHours quietHours = quiet(
            true, "America/New_York", "01:30", "02:30", DayOfWeek.SUNDAY);

        NotificationQuietHoursEvaluator.Evaluation result = evaluator.evaluate(
            quietHours, Instant.parse("2026-11-01T06:15:00Z"));

        assertTrue(result.active());
        assertEquals(Instant.parse("2026-11-01T07:30:00Z"), result.nextTransitionAt());
    }

    @Test
    void fallOverlapUsesTheLaterOccurrenceForAnAmbiguousEnd() {
        NotificationQuietHours quietHours = quiet(
            true, "America/New_York", "01:00", "01:30", DayOfWeek.SUNDAY);

        NotificationQuietHoursEvaluator.Evaluation result = evaluator.evaluate(
            quietHours, Instant.parse("2026-11-01T06:15:00Z"));

        assertTrue(result.active());
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), result.nextTransitionAt());
    }

    private static NotificationQuietHours quiet(
        boolean enabled,
        String timezone,
        String start,
        String end,
        DayOfWeek... days
    ) {
        NotificationQuietHours quietHours = new NotificationQuietHours();
        quietHours.setEnabled(enabled);
        quietHours.setTimezone(timezone);
        quietHours.setStartLocal(start);
        quietHours.setEndLocal(end);
        int mask = 0;
        for (DayOfWeek day : days) {
            mask |= 1 << (day.getValue() - 1);
        }
        quietHours.setDaysMask(mask);
        return quietHours;
    }
}
