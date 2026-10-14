package ooo.klae.connex.backend.delivery.provider.smtp;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.logging.Filter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import ooo.klae.connex.backend.delivery.CampaignDeliveryDiagnostics;

/** Scrubs Angus diagnostics and suppresses MIME DATA before JUL handlers can publish them. */
final class SmtpDiagnosticLogFilter implements Filter {
    private static final ThreadLocal<Boolean> DATA_BODY = new ThreadLocal<>();
    private static final Logger[] LOGGERS = {
            Logger.getLogger("org.eclipse.angus.mail.smtp"),
            Logger.getLogger("org.eclipse.angus.mail.smtp.protocol")
    };
    private final Filter previous;

    SmtpDiagnosticLogFilter(Filter previous) {
        this.previous = previous;
    }

    /** Installs once per named logger, retaining existing filtering and all diagnostic levels. */
    static synchronized void install() {
        for (Logger logger : LOGGERS) {
            if (!(logger.getFilter() instanceof SmtpDiagnosticLogFilter)) {
                logger.setFilter(new SmtpDiagnosticLogFilter(logger.getFilter()));
            }
        }
    }

    /** Starts suppression after the DATA command and its successful server response. */
    static void beginDataBody() {
        DATA_BODY.set(true);
    }

    /** Clears the sending thread's state after completion or transport cleanup. */
    static void endDataBody() {
        DATA_BODY.remove();
    }

    @Override
    public boolean isLoggable(LogRecord record) {
        if (Boolean.TRUE.equals(DATA_BODY.get()) && record.getLoggerName() != null
                && record.getLoggerName().endsWith(".protocol")) {
            if (!".".equals(record.getMessage())) {
                return false;
            }
            endDataBody();
        }
        record.setMessage(CampaignDeliveryDiagnostics.scrub(record.getMessage(), null));
        Object[] parameters = record.getParameters();
        if (parameters != null) {
            Object[] scrubbed = parameters.clone();
            for (int index = 0; index < scrubbed.length; index++) {
                String value = String.valueOf(scrubbed[index]);
                String safe = CampaignDeliveryDiagnostics.scrub(value, null);
                if (!value.equals(safe)) {
                    scrubbed[index] = safe;
                }
            }
            record.setParameters(scrubbed);
        }
        if (record.getThrown() != null) {
            record.setThrown(scrubThrowable(record.getThrown(), new IdentityHashMap<>()));
        }
        return previous == null || previous.isLoggable(record);
    }

    private static Throwable scrubThrowable(Throwable source, Map<Throwable, Throwable> copies) {
        Throwable existing = copies.get(source);
        if (existing != null) {
            return existing;
        }
        Throwable copy = new ScrubbedThrowable(source);
        copies.put(source, copy);
        copy.setStackTrace(source.getStackTrace());
        if (source.getCause() != null) {
            copy.initCause(scrubThrowable(source.getCause(), copies));
        }
        for (Throwable suppressed : source.getSuppressed()) {
            copy.addSuppressed(scrubThrowable(suppressed, copies));
        }
        return copy;
    }

    private static final class ScrubbedThrowable extends Throwable {
        private final String description;

        private ScrubbedThrowable(Throwable source) {
            super(CampaignDeliveryDiagnostics.scrub(source.toString(), null));
            description = CampaignDeliveryDiagnostics.scrub(source.toString(), null);
        }

        @Override
        public String toString() {
            return description;
        }
    }
}
