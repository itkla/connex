package ooo.klae.connex.backend.exceptions;

/**
 * Raised while reading an API request body after the configured byte limit is exceeded.
 */
public class RequestBodyTooLargeException extends RuntimeException {
    public RequestBodyTooLargeException(long limitBytes) {
        super("Request body exceeds " + limitBytes + " bytes");
    }
}
