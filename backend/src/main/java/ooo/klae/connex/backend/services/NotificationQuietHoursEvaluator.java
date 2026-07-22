package ooo.klae.connex.backend.services;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.beans.NotificationQuietHours;

/**
 * Evaluates recurring quiet-hours intervals on the UTC timeline.
 */
@Component
public class NotificationQuietHoursEvaluator {

    /**
     * Evaluates whether quiet hours are active and the next actual state transition.
     * @param quietHours validated quiet-hours configuration
     * @param asOf UTC snapshot instant
     * @return active state and next transition
     */
    public Evaluation evaluate(NotificationQuietHours quietHours, Instant asOf) {
        if (!quietHours.isEnabled()) {
            return new Evaluation(false, null);
        }
        ZoneId zone = ZoneId.of(quietHours.getTimezone());
        LocalTime start = LocalTime.parse(quietHours.getStartLocal());
        LocalTime end = LocalTime.parse(quietHours.getEndLocal());
        LocalDate localDate = asOf.atZone(zone).toLocalDate();
        boolean active = isActive(quietHours.getDaysMask(), start, end, zone, asOf);
        List<Instant> candidates = new ArrayList<>();
        for (int offset = -1; offset <= 8; offset++) {
            LocalDate startDate = localDate.plusDays(offset);
            if (!startsOn(quietHours.getDaysMask(), startDate.getDayOfWeek())) {
                continue;
            }
            Instant startInstant = resolve(startDate, start, zone, false);
            LocalDate endDate = end.isAfter(start) ? startDate : startDate.plusDays(1);
            Instant endInstant = resolve(endDate, end, zone, true);
            if (startInstant.isAfter(asOf)) {
                candidates.add(startInstant);
            }
            if (endInstant.isAfter(asOf)) {
                candidates.add(endInstant);
            }
        }
        Instant next = candidates.stream()
            .distinct()
            .sorted(Comparator.naturalOrder())
            .filter(candidate -> isActive(
                quietHours.getDaysMask(), start, end, zone, candidate.minusNanos(1))
                != isActive(quietHours.getDaysMask(), start, end, zone, candidate))
            .findFirst()
            .orElse(null);
        return new Evaluation(active, next);
    }

    private static boolean isActive(
        int daysMask,
        LocalTime start,
        LocalTime end,
        ZoneId zone,
        Instant instant
    ) {
        LocalDate localDate = instant.atZone(zone).toLocalDate();
        for (LocalDate startDate : List.of(localDate.minusDays(1), localDate)) {
            if (!startsOn(daysMask, startDate.getDayOfWeek())) {
                continue;
            }
            Instant startInstant = resolve(startDate, start, zone, false);
            LocalDate endDate = end.isAfter(start) ? startDate : startDate.plusDays(1);
            Instant endInstant = resolve(endDate, end, zone, true);
            if (!instant.isBefore(startInstant) && instant.isBefore(endInstant)) {
                return true;
            }
        }
        return false;
    }

    private static Instant resolve(LocalDate date, LocalTime time, ZoneId zone, boolean endBoundary) {
        LocalDateTime localDateTime = LocalDateTime.of(date, time);
        List<ZoneOffset> validOffsets = zone.getRules().getValidOffsets(localDateTime);
        if (validOffsets.size() == 2) {
            ZoneOffset offset = endBoundary ? validOffsets.getLast() : validOffsets.getFirst();
            return localDateTime.toInstant(offset);
        }
        return localDateTime.atZone(zone).toInstant();
    }

    private static boolean startsOn(int daysMask, DayOfWeek day) {
        return (daysMask & (1 << (day.getValue() - 1))) != 0;
    }

    /**
     * Quiet-hours state at a snapshot and its next change.
     * @param active whether the snapshot is quiet
     * @param nextTransitionAt next instant when the state changes
     */
    public record Evaluation(boolean active, Instant nextTransitionAt) {
    }
}
