package ooo.klae.connex.backend.exceptions;

/**
 * Raised when encrypted integration secrets cannot be used because their
 * key-encryption key is missing, disabled, revoked, or mismatched.
 */
public class SecretUnavailableException extends RuntimeException {
    public SecretUnavailableException(String message) {
        super(message);
    }

    public SecretUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
