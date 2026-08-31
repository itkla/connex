package ooo.klae.connex.backend.work;

/** Signals that authoritative source rows cannot be projected safely. */
public class InvalidWorkItemSourceRowsException extends RuntimeException {
    /** Creates a provider-local malformed-row failure without exposing row content. */
    public InvalidWorkItemSourceRowsException() {
        super("Work item source rows are invalid");
    }

    /** Creates a provider-local malformed-row failure with an internal cause. */
    public InvalidWorkItemSourceRowsException(Throwable cause) {
        super("Work item source rows are invalid", cause);
    }
}
