package ooo.klae.connex.backend.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured logging fallback for application error reports.
 */
public class LoggingErrorReporter implements ErrorReporter {
    private static final int MAX_CORRELATION_ID_LENGTH = 64;
    private static final int MAX_MESSAGE_LENGTH = 1_000;
    private static final int MAX_DETAIL_LENGTH = 8_192;
    private static final int MAX_PATH_LENGTH = 300;
    private static final Logger log = LoggerFactory.getLogger(LoggingErrorReporter.class);

    @Override
    public void report(ReportedError error) {
        log.atError()
                .addKeyValue("source", error.source().name())
                .addKeyValue("correlationId",
                        sanitize(error.correlationId(), MAX_CORRELATION_ID_LENGTH, false))
                .addKeyValue("workspaceId", error.workspaceId())
                .addKeyValue("userId", error.userId())
                .addKeyValue("message", sanitize(error.message(), MAX_MESSAGE_LENGTH, false))
                .addKeyValue("detail", sanitize(error.detail(), MAX_DETAIL_LENGTH, true))
                .addKeyValue("path", sanitize(error.path(), MAX_PATH_LENGTH, false))
                .log("Application error reported");
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
