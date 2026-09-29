package ooo.klae.connex.backend.exceptions;

/**
 * Stable field error for a password longer than the credential encoder can store.
 *
 * <p>BCrypt consumes at most 72 bytes of input and the encoder refuses anything longer, so a longer
 * candidate is rejected before screening rather than silently truncated or failed as a server error.
 * The message is a constant and never carries the candidate.
 */
public class PasswordTooLongException extends RuntimeException {
    public static final String CODE = "PASSWORD_TOO_LONG";
    public static final String MESSAGE = "Password must be at most 72 bytes (72 ASCII characters). Choose a shorter password.";

    private final String field;

    public PasswordTooLongException(String field) {
        super(MESSAGE);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
