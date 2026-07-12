package ooo.klae.connex.backend.exceptions;

/**
 * Raised when a request conflicts with existing data in a way the caller must
 * resolve first — e.g. deleting an account that still owns authored content.
 * Mapped to HTTP 409 with the exception's message, mirroring the semantics the
 * database RESTRICT constraints used to enforce (#440 increment 3).
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
