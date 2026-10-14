package ooo.klae.connex.backend.delivery.provider.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.Test;

/** Exercises library log records before JUL handlers and the SLF4J bridge see them. */
class SmtpDiagnosticLogFilterTest {
    @Test
    void scrubsMessageParametersAndThrowableGraphWithoutChangingTheSourceException() {
        String bearer = "AB".repeat(32);
        IllegalStateException cause = new IllegalStateException("cause " + bearer);
        IllegalArgumentException failure = new IllegalArgumentException("554 rejected " + bearer, cause);
        failure.addSuppressed(new IllegalStateException("suppressed " + bearer));
        cause.addSuppressed(failure);
        LogRecord record = new LogRecord(Level.FINE, "reply " + bearer + " retained {0} {1}");
        record.setParameters(new Object[] {"echo " + bearer, 554});
        record.setThrown(failure);
        assertTrue(new SmtpDiagnosticLogFilter(null).isLoggable(record));
        assertEquals(Level.FINE, record.getLevel());
        assertEquals("reply [redacted] retained {0} {1}", record.getMessage());
        assertEquals("echo [redacted]", record.getParameters()[0]);
        assertEquals(554, record.getParameters()[1]);
        assertNotSame(failure, record.getThrown());
        assertEquals("554 rejected " + bearer, failure.getMessage());
        assertEquals("java.lang.IllegalArgumentException: 554 rejected [redacted]", record.getThrown().getMessage());
        assertEquals("java.lang.IllegalStateException: cause [redacted]", record.getThrown().getCause().getMessage());
        assertEquals("java.lang.IllegalStateException: suppressed [redacted]", record.getThrown().getSuppressed()[0].getMessage());
        StringWriter output = new StringWriter();
        record.getThrown().printStackTrace(new PrintWriter(output));
        assertFalse(output.toString().contains(bearer));
        assertTrue(output.toString().contains("CIRCULAR REFERENCE"));
    }

    @Test
    void preservesAnExistingFiltersRejectionAfterScrubbing() {
        LogRecord record = new LogRecord(Level.FINEST, "554 rejected " + "a".repeat(64));
        assertFalse(new SmtpDiagnosticLogFilter(scrubbed -> {
            assertEquals("554 rejected [redacted]", scrubbed.getMessage());
            return false;
        }).isLoggable(record));
    }
}
