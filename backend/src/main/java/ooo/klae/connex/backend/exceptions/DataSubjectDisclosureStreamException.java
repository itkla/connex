package ooo.klae.connex.backend.exceptions;

/** Signals an interrupted release; a committed response must remain visibly truncated. */
public class DataSubjectDisclosureStreamException extends RuntimeException {
    public DataSubjectDisclosureStreamException(Throwable cause) {
        super("Disclosure release did not complete", cause);
    }
}
