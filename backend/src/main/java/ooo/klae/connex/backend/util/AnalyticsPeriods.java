package ooo.klae.connex.backend.util;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * Builds bounded analytics windows and viewer-local calendar periods.
 *
 * <p>Day buckets follow local calendar days, week buckets follow ISO-8601 weeks
 * beginning on Monday, and month buckets begin on the first day of the month.
 * Period boundaries are full calendar boundaries while the window remains the
 * half-open local interval from {@code from} at midnight through the day after
 * {@code to} at midnight. Consumers must apply both boundaries so partial edge
 * periods are clipped. UTC timestamp boundaries are derived independently for
 * every local boundary so daylight-saving transitions remain correct.
 */
public final class AnalyticsPeriods {
    private static final int MAX_WINDOW_DAYS = 731;
    private static final int MAX_BUCKETS = 120;

    private AnalyticsPeriods() {
    }

    /**
     * Resolves an optional date window, ignoring window-only parameters when both dates are absent.
     */
    public static Optional<Window> optionalWindow(
            String from, String to, String timezone, String tzOffset) {
        if (from == null && to == null) {
            return Optional.empty();
        }
        if (from == null || to == null) {
            throw new BadRequestException("from and to must be provided together");
        }
        return Optional.of(window(from, to, timezone, tzOffset));
    }

    /**
     * Resolves a required date window.
     */
    public static Window requiredWindow(
            String from, String to, String timezone, String tzOffset) {
        if (from == null) {
            throw new BadRequestException("from is required");
        }
        if (to == null) {
            throw new BadRequestException("to is required");
        }
        return window(from, to, timezone, tzOffset);
    }

    /**
     * Builds oldest-first aligned periods for a validated window.
     */
    public static List<AnalyticsPeriod> periods(Window window, String granularity) {
        Granularity parsedGranularity = parseGranularity(granularity);
        LocalDate firstStart = periodStart(window.fromDate(), parsedGranularity);
        List<AnalyticsPeriod> periods = new ArrayList<>();
        LocalDate startDate = firstStart;
        while (!startDate.isAfter(window.toDate())) {
            if (periods.size() == MAX_BUCKETS) {
                throw new BadRequestException("granularity produces more than 120 calendar buckets");
            }
            LocalDate endDate = nextPeriodStart(startDate, parsedGranularity);
            periods.add(new AnalyticsPeriod(
                periods.size(),
                startDate,
                endDate,
                toUtc(startDate, window.timezone()),
                toUtc(endDate, window.timezone())));
            startDate = endDate;
        }
        return List.copyOf(periods);
    }

    /**
     * Resolves the existing timezone/tzOffset query contract, defaulting to UTC.
     */
    public static String resolveTimezone(String timezone, String tzOffset) {
        boolean hasTimezone = timezone != null && !timezone.isBlank();
        boolean hasOffset = tzOffset != null && !tzOffset.isBlank();
        if (hasTimezone && hasOffset) {
            throw new BadRequestException("Specify either timezone or tzOffset, not both");
        }
        if (!hasTimezone && !hasOffset) {
            return "UTC";
        }
        String value = hasTimezone ? timezone.trim() : tzOffset.trim();
        try {
            return hasTimezone ? ZoneId.of(value).getId() : ZoneOffset.of(value).getId();
        } catch (DateTimeException exception) {
            throw new BadRequestException(hasTimezone
                ? "Invalid timezone: " + value
                : "tzOffset must be a UTC offset like +09:00 or -05:00");
        }
    }

    private static Window window(String from, String to, String timezone, String tzOffset) {
        LocalDate fromDate = parseDate(from, "from");
        LocalDate toDate = parseDate(to, "to");
        if (fromDate.isAfter(toDate)) {
            throw new BadRequestException("from must be on or before to");
        }
        long windowDays = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        if (windowDays > MAX_WINDOW_DAYS) {
            throw new BadRequestException("from and to must span 731 days or fewer");
        }
        ZoneId zone = ZoneId.of(resolveTimezone(timezone, tzOffset));
        try {
            LocalDate endDate = toDate.plusDays(1);
            LocalDate previousFromDate = fromDate.minusDays(windowDays);
            return new Window(
                fromDate,
                toDate,
                endDate,
                zone,
                toUtc(fromDate, zone),
                toUtc(endDate, zone),
                toUtc(previousFromDate, zone),
                toUtc(fromDate, zone));
        } catch (DateTimeException exception) {
            throw new BadRequestException("from and to are outside the supported date range");
        }
    }

    private static LocalDate parseDate(String value, String parameter) {
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException exception) {
            throw new BadRequestException(parameter + " must be an ISO date in yyyy-MM-dd format");
        }
    }

    private static Granularity parseGranularity(String granularity) {
        if (granularity == null || granularity.isBlank()) {
            throw new BadRequestException("granularity is required");
        }
        return switch (granularity) {
            case "day" -> Granularity.DAY;
            case "week" -> Granularity.WEEK;
            case "month" -> Granularity.MONTH;
            default -> throw new BadRequestException("granularity must be one of: day, week, month");
        };
    }

    private static LocalDate periodStart(LocalDate date, Granularity granularity) {
        return switch (granularity) {
            case DAY -> date;
            case WEEK -> date.with(WeekFields.ISO.dayOfWeek(), 1);
            case MONTH -> date.withDayOfMonth(1);
        };
    }

    private static LocalDate nextPeriodStart(LocalDate startDate, Granularity granularity) {
        return switch (granularity) {
            case DAY -> startDate.plusDays(1);
            case WEEK -> startDate.plusWeeks(1);
            case MONTH -> startDate.plusMonths(1);
        };
    }

    private static LocalDateTime toUtc(LocalDate date, ZoneId timezone) {
        return date.atStartOfDay(timezone)
            .withZoneSameInstant(ZoneOffset.UTC)
            .toLocalDateTime();
    }

    private enum Granularity {
        DAY,
        WEEK,
        MONTH
    }

    /**
     * One inclusive local-date window with half-open current and previous UTC bounds.
     */
    public record Window(
        LocalDate fromDate,
        LocalDate toDate,
        LocalDate endDate,
        ZoneId timezone,
        LocalDateTime startUtc,
        LocalDateTime endUtc,
        LocalDateTime previousStartUtc,
        LocalDateTime previousEndUtc
    ) {
    }

    /**
     * One full viewer-local calendar period with exclusive local and UTC end boundaries.
     */
    public record AnalyticsPeriod(
        int index,
        LocalDate startDate,
        LocalDate endDate,
        LocalDateTime startUtc,
        LocalDateTime endUtc
    ) {
    }
}
