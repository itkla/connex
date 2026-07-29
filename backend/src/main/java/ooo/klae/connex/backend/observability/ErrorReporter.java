package ooo.klae.connex.backend.observability;

/**
 * Replaceable sink for sanitized application error reports.
 */
public interface ErrorReporter {

    /**
     * Reports one server or client error.
     *
     * @param error the error metadata to report
     */
    void report(ReportedError error);
}
