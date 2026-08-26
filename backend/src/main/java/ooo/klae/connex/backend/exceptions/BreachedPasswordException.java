package ooo.klae.connex.backend.exceptions;

/**
 * Stable field error for a password found in a known breach corpus.
 */
public class BreachedPasswordException extends RuntimeException {
    public static final String CODE = "BREACHED_PASSWORD";
    public static final String MESSAGE = "This password appeared in a known data breach. Choose a different password that you do not use anywhere else.";

    private final String field;

    public BreachedPasswordException(String field) {
        super(MESSAGE);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
