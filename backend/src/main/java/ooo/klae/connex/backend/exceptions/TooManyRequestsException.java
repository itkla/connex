package ooo.klae.connex.backend.exceptions;

/**
 * Signals that a client has exceeded a rate limit and should retry later.
 * Mapped to HTTP 429 by {@code GlobalExceptionHandler}.
 */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
