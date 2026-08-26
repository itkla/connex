package ooo.klae.connex.backend.exceptions;

/** Signals that the authenticated caller is not authorized to perform an operation. */
public class ForbiddenException extends RuntimeException {
    /** Stable API error code for authorization failures without a more specific subtype. */
    public static final String CODE = "FORBIDDEN";

    public ForbiddenException(String message) {
        super(message);
    }

    /** Returns the stable API error code for this failure. */
    public String getCode() {
        return CODE;
    }
}
