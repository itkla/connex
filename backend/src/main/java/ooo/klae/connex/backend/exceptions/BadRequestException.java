package ooo.klae.connex.backend.exceptions;

/** Signals that an API request cannot be accepted as submitted. */
public class BadRequestException extends RuntimeException {
    /** Stable API error code for invalid requests without a more specific subtype. */
    public static final String CODE = "BAD_REQUEST";

    public BadRequestException(String message) {
        super(message);
    }

    /** Returns the stable API error code for this failure. */
    public String getCode() {
        return CODE;
    }
}
