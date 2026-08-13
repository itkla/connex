package ooo.klae.connex.backend.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Structured logging fallback for application error reports.
 *
 * <p>The multiline {@code detail} field keeps {@code \n}/{@code \t} for readable stack traces,
 * which is injection-safe only while the production encoder is structured JSON
 * ({@code logging.structured.format.console}) that escapes key-value payloads. Any replacement
 * reporter or plain-text pattern that renders these key-values must escape newlines itself.
 *
 * <p>Key-value names must avoid the reserved ECS top-level fields ({@code message},
 * {@code @timestamp}, {@code log}, …): the structured JSON encoder writes those itself and
 * throws on duplicates, which silently discards the whole error report.
 */
public class LoggingErrorReporter implements ErrorReporter {
    private static final int MAX_CORRELATION_ID_LENGTH = 64;
    private static final int MAX_MESSAGE_LENGTH = 1_000;
    private static final int MAX_DETAIL_LENGTH = 8_192;
    private static final int MAX_PATH_LENGTH = 300;
    private static final Logger log = LoggerFactory.getLogger(LoggingErrorReporter.class);

    @Override
    public void report(ReportedError error) {
        LoggingEventBuilder event = log.atError()
                .addKeyValue("source", error.source().name())
                .addKeyValue("workspaceId", error.workspaceId())
                .addKeyValue("userId", error.userId())
                .addKeyValue("errorMessage", sanitize(error.message(), MAX_MESSAGE_LENGTH, false))
                .addKeyValue("detail", sanitize(error.detail(), MAX_DETAIL_LENGTH, true))
                .addKeyValue("path", sanitize(error.path(), MAX_PATH_LENGTH, false));
        if (MDC.get(CorrelationIds.MDC_KEY) == null) {
            event.addKeyValue(
                    CorrelationIds.MDC_KEY,
                    sanitize(error.correlationId(), MAX_CORRELATION_ID_LENGTH, false));
        }
        event.log("Application error reported");
    }

    private static String sanitize(String value, int maxLength, boolean multiline) {
        if (value == null) {
            return null;
        }
        StringBuilder sanitized = new StringBuilder(Math.min(value.length(), maxLength));
        value.codePoints().forEachOrdered(codePoint -> {
            if (sanitized.length() >= maxLength || rejected(codePoint, multiline)) {
                return;
            }
            int width = Character.charCount(codePoint);
            if (sanitized.length() + width <= maxLength) {
                sanitized.appendCodePoint(codePoint);
            }
        });
        return sanitized.toString();
    }

    private static boolean rejected(int codePoint, boolean multiline) {
        if (multiline && (codePoint == '\n' || codePoint == '\t')) {
            return false;
        }
        int type = Character.getType(codePoint);
        return type == Character.CONTROL
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }
}
