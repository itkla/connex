package ooo.klae.connex.backend.exceptions;

/**
 * Signals that a client has exceeded a rate limit and should retry later.
 * Mapped to HTTP 429 by {@code GlobalExceptionHandler}.
 */
public class TooManyRequestsException extends RuntimeException {
    /** Stable API error code for rate-limit failures. */
    public static final String CODE = "TOO_MANY_REQUESTS";

    public TooManyRequestsException(String message) {
        super(message);
    }

    /** Returns the stable API error code for this failure. */
    public String getCode() {
        return CODE;
    }
}
