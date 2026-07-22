package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.SnoozeRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * Resolves notification snooze requests to one immutable UTC instant.
 */
@Component
@RequiredArgsConstructor
public class NotificationSnoozeResolver {
    private static final Duration MAX_HORIZON = Duration.ofDays(30);
    private static final DateTimeFormatter DATABASE_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Clock clock;

    /**
     * Validates and resolves a preset, custom instant, or legacy hour alias.
     * @param request snooze request
     * @return resolved UTC instant and calculation timezone
     */
    public Resolution resolve(SnoozeRequest request) {
        if (request == null) {
            throw new BadRequestException("Snooze request is required");
        }
        String preset = blankToNull(request.getPreset());
        String until = blankToNull(request.getUntil());
        Integer hours = request.getHours();
        int selectors = (preset == null ? 0 : 1) + (until == null ? 0 : 1) + (hours == null ? 0 : 1);
        if (selectors != 1) {
            throw new BadRequestException("Provide exactly one of preset, until, or hours");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String timezone;
        Instant resolved;
        if (hours != null) {
            if (hours < 1 || hours > 720) {
                throw new BadRequestException("Snooze hours must be between 1 and 720");
            }
            timezone = TimezoneSupport.validateIana(request.getTimezone(), "UTC");
            resolved = now.plus(Duration.ofHours(hours));
        } else {
            timezone = TimezoneSupport.validateIana(request.getTimezone(), null);
            if (until != null) {
                resolved = parseInstant(until);
            } else {
                resolved = resolvePreset(preset, ZoneId.of(timezone), now);
            }
        }
        if (!resolved.isAfter(now)) {
            throw new BadRequestException("Snooze time must be in the future");
        }
        if (resolved.isAfter(now.plus(MAX_HORIZON))) {
            throw new BadRequestException("Snooze time must be within 30 days");
        }
        resolved = resolved.truncatedTo(ChronoUnit.SECONDS);
        if (!resolved.isAfter(now)) {
            throw new BadRequestException("Snooze time must be in the future at second precision");
        }
        return new Resolution(resolved, timezone);
    }

    private static Instant resolvePreset(String preset, ZoneId zone, Instant now) {
        LocalDateTime localNow = LocalDateTime.ofInstant(now, zone);
        LocalDateTime target = switch (preset) {
            case "later_today" -> LocalDateTime.of(localNow.toLocalDate(), LocalTime.of(17, 0));
            case "tomorrow_morning" -> LocalDateTime.of(
                localNow.toLocalDate().plusDays(1), LocalTime.of(9, 0));
            case "next_week" -> LocalDateTime.of(
                localNow.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)),
                LocalTime.of(9, 0));
            default -> throw new BadRequestException("Unsupported snooze preset: " + preset);
        };
        return resolveLocal(target, zone);
    }

    static Instant resolveLocal(LocalDateTime target, ZoneId zone) {
        return target.atZone(zone).toInstant().truncatedTo(ChronoUnit.SECONDS);
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BadRequestException("Snooze until must be an ISO-8601 UTC instant");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Resolved snooze storage values.
     * @param until absolute UTC instant
     * @param timezone IANA calculation timezone
     */
    public record Resolution(Instant until, String timezone) {

        /**
         * Formats the UTC instant for the MySQL DATETIME column.
         * @return UTC database timestamp
         */
        public String databaseTimestamp() {
            return LocalDateTime.ofInstant(until, ZoneOffset.UTC).format(DATABASE_TIMESTAMP);
        }
    }
}
