package ooo.klae.connex.backend.util;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Shared, tolerant parsing of the UTC {@code yyyy-MM-dd HH:mm:ss} datetimes that MySQL DATETIME
 * columns are read back as. Centralized so warmth/replay/risk scoring share one implementation
 * and cannot silently diverge in how they handle date-only or minute-precision inputs.
 */
public final class DateTimes {

    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DateTimes() {
    }

    /**
     * Parses a UTC {@code yyyy-MM-dd HH:mm:ss} datetime to epoch millis, tolerating an ISO
     * {@code T} separator, a trailing {@code Z}, fractional seconds, and date-only
     * ({@code yyyy-MM-dd}) or minute-precision ({@code yyyy-MM-dd HH:mm}) inputs.
     * @param value the datetime string, possibly null/blank
     * @return epoch millis, or null when the value is null/blank/unparseable
     */
    public static Long epochMillis(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace('T', ' ');
        int dot = normalized.indexOf('.');
        if (dot > 0) {
            normalized = normalized.substring(0, dot);
        }
        if (normalized.endsWith("Z")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.length() == 10) {
            normalized = normalized + " 00:00:00";
        } else if (normalized.length() == 16) {
            normalized = normalized + ":00";
        }
        try {
            return LocalDateTime.parse(normalized, MYSQL_DATETIME).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
