package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.AuthService;

/** Resolves assistant calendar expressions against the authenticated actor's persisted timezone. */
@Component
@RequiredArgsConstructor
public class AiAssistantDateResolver {
    private static final Pattern NEXT_DAY = Pattern.compile(
            "(?:(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))\\s+)?next\\s+"
                    + "(monday|tuesday|wednesday|thursday|friday|saturday|sunday)"
                    + "(?:\\s+(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)))?");
    private static final Pattern TOMORROW = Pattern.compile(
            "tomorrow(?:\\s+(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)))?");
    private static final DateTimeFormatter MYSQL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AuthService authService;
    private final Clock clock;

    /** Resolves an ISO or supported relative timestamp to actor-local and UTC values. */
    public ResolvedDateTime resolveDateTime(String expression) {
        if (expression == null || expression.isBlank()) {
            throw AiAssistantLoopException.refusedArguments("missing_datetime");
        }
        ZoneId timezone = actorTimezone();
        String value = expression.trim();
        Instant absolute = absoluteInstant(value);
        if (absolute != null) {
            return absoluteResolution(absolute, timezone);
        }
        try {
            return resolution(LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME), timezone);
        } catch (DateTimeParseException exception) {
            return resolveRelative(value, timezone);
        }
    }

    /** Resolves an ISO or supported relative calendar date in the actor's timezone. */
    public LocalDate resolveDate(String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        String value = expression.trim();
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException exception) {
            ZoneId timezone = actorTimezone();
            String normalized = normalize(value);
            LocalDate today = LocalDate.now(clock.withZone(timezone));
            Matcher next = NEXT_DAY.matcher(normalized);
            if (next.matches()) {
                return today.with(TemporalAdjusters.next(dayOfWeek(next.group(2))));
            }
            Matcher tomorrow = TOMORROW.matcher(normalized);
            if (tomorrow.matches()) {
                return today.plusDays(1);
            }
            return resolveDateTime(value).local().toLocalDate();
        }
    }

    private ResolvedDateTime resolveRelative(String expression, ZoneId timezone) {
        String normalized = normalize(expression);
        LocalDate today = LocalDate.now(clock.withZone(timezone));
        Matcher next = NEXT_DAY.matcher(normalized);
        if (next.matches()) {
            String timeText = next.group(1) != null ? next.group(1) : next.group(3);
            LocalDate date = today.with(TemporalAdjusters.next(dayOfWeek(next.group(2))));
            return resolution(LocalDateTime.of(date, requireTime(timeText)), timezone);
        }
        Matcher tomorrow = TOMORROW.matcher(normalized);
        if (tomorrow.matches()) {
            return resolution(LocalDateTime.of(
                    today.plusDays(1), requireTime(tomorrow.group(1))), timezone);
        }
        throw AiAssistantLoopException.refusedArguments("unsupported_date_expression");
    }

    private ZoneId actorTimezone() {
        User actor = authService.getCurrentUser();
        String timezone = actor.getTimezone();
        try {
            return ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone.trim());
        } catch (DateTimeException exception) {
            throw new BadRequestException("Authenticated user has an invalid timezone");
        }
    }

    private static Instant absoluteInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            try {
                return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static LocalTime requireTime(String value) {
        if (value == null || value.isBlank()) {
            throw AiAssistantLoopException.refusedArguments("missing_relative_time");
        }
        String compact = value.toLowerCase(Locale.ROOT).replace(" ", "");
        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(compact.contains(":") ? "h:mma" : "ha")
                .toFormatter(Locale.ROOT);
        try {
            return LocalTime.parse(compact, formatter);
        } catch (DateTimeParseException exception) {
            throw AiAssistantLoopException.refusedArguments("invalid_relative_time");
        }
    }

    private static DayOfWeek dayOfWeek(String value) {
        return DayOfWeek.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static String normalize(String expression) {
        return expression.toLowerCase(Locale.ROOT)
                .replace(',', ' ')
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static ResolvedDateTime resolution(LocalDateTime local, ZoneId timezone) {
        List<ZoneOffset> validOffsets = timezone.getRules().getValidOffsets(local);
        if (validOffsets.isEmpty()) {
            throw AiAssistantLoopException.refusedArguments("nonexistent_local_time");
        }
        if (validOffsets.size() != 1) {
            throw AiAssistantLoopException.refusedArguments("ambiguous_local_time");
        }
        return resolved(local, local.toInstant(validOffsets.getFirst()), timezone);
    }

    private static ResolvedDateTime absoluteResolution(Instant instant, ZoneId timezone) {
        return resolved(instant.atZone(timezone).toLocalDateTime(), instant, timezone);
    }

    private static ResolvedDateTime resolved(
            LocalDateTime local, Instant instant, ZoneId timezone) {
        LocalDateTime utc = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        return new ResolvedDateTime(local, utc, timezone, MYSQL.format(utc));
    }

    /** One resolved actor-local timestamp and its canonical UTC persistence value. */
    public record ResolvedDateTime(
            LocalDateTime local,
            LocalDateTime utc,
            ZoneId timezone,
            String mysqlUtc) {
    }
}
