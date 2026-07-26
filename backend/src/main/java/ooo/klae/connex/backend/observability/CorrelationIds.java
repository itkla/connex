package ooo.klae.connex.backend.observability;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/**
 * Shared correlation identifier names and validation.
 */
public final class CorrelationIds {
    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern VALID_VALUE = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private CorrelationIds() {
    }

    /**
     * Returns the valid identifier in the current logging context or creates a new one.
     *
     * @return a valid correlation identifier
     */
    public static String current() {
        String value = MDC.get(MDC_KEY);
        return isValid(value) ? value : generate();
    }

    /**
     * Returns whether the value is safe to place in response headers and logs.
     *
     * @param value the candidate identifier
     * @return whether the value satisfies the strict correlation identifier contract
     */
    public static boolean isValid(String value) {
        return value != null && VALID_VALUE.matcher(value).matches();
    }

    /**
     * Creates a new correlation identifier.
     *
     * @return a UUID-form correlation identifier
     */
    public static String generate() {
        return UUID.randomUUID().toString();
    }
}
