package ooo.klae.connex.backend.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.KeyValuePair;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ooo.klae.connex.backend.observability.ReportedError.Source;

class LoggingErrorReporterTest {

    @Test
    void emitsSanitizedStructuredFields() {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingErrorReporter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new LoggingErrorReporter().report(new ReportedError(
                    Source.CLIENT,
                    "corr\nid\u2028x",
                    7,
                    9,
                    "message\r\n\u0000end\u2029",
                    "line1\r\nline2\t\u0000\u2028line3",
                    "/path\nforged"));

            ILoggingEvent event = appender.list.getFirst();
            Map<String, Object> values = keyValues(event);

            assertEquals("CLIENT", values.get("source"));
            assertEquals("corridx", values.get("correlationId"));
            assertEquals(7, values.get("workspaceId"));
            assertEquals(9, values.get("userId"));
            assertEquals("messageend", values.get("message"));
            assertEquals("line1\nline2\tline3", values.get("detail"));
            assertEquals(RequestPathRedactor.UNKNOWN_ROUTE, values.get("path"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void independentlyTruncatesEveryTextFieldWithoutBrokenSurrogates() {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingErrorReporter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String suffix = "\uD83D\uDE00";
            new LoggingErrorReporter().report(new ReportedError(
                    Source.SERVER,
                    "c".repeat(64) + suffix,
                    null,
                    null,
                    "m".repeat(1_000) + suffix,
                    "d".repeat(8_192) + suffix,
                    "p".repeat(300) + suffix));

            Map<String, Object> keyed = keyValues(appender.list.getFirst());

            assertEquals(64, ((String) keyed.get("correlationId")).length());
            assertEquals(1_000, ((String) keyed.get("message")).length());
            assertEquals(8_192, ((String) keyed.get("detail")).length());
            assertEquals(RequestPathRedactor.UNKNOWN_ROUTE, keyed.get("path"));
            assertFalse(((String) keyed.get("message")).endsWith("\uD83D"));
            assertTrue(keyed.size() >= 7);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void existingMdcCorrelationStillLogsTheUnderlyingException() {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingErrorReporter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put(CorrelationIds.MDC_KEY, "request-correlation");
        try {
            new LoggingErrorReporter().report(new ReportedError(
                    Source.SERVER,
                    "reported-correlation",
                    7,
                    9,
                    "Provider call failed",
                    "IllegalStateException: provider transport failed",
                    "/api/ai/assistant/sessions/{sessionId}/turns/{turnId}"));

            ILoggingEvent event = appender.list.getFirst();
            Map<String, Object> values = keyValues(event);

            assertEquals("request-correlation",
                    event.getMDCPropertyMap().get(CorrelationIds.MDC_KEY));
            assertFalse(values.containsKey(CorrelationIds.MDC_KEY));
            assertEquals(
                    "IllegalStateException: provider transport failed",
                    values.get("detail"));
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (KeyValuePair pair : event.getKeyValuePairs()) {
            values.put(pair.key, pair.value);
        }
        return values;
    }
}
