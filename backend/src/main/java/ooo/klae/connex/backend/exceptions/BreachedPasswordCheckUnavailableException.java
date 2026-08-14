package ooo.klae.connex.backend.exceptions;

/**
 * Stable field error when a credential write cannot safely bypass breach screening.
 */
public class BreachedPasswordCheckUnavailableException extends RuntimeException {
    public static final String CODE = "BREACHED_PASSWORD_CHECK_UNAVAILABLE";
    public static final String MESSAGE = "Password breach screening is temporarily unavailable. Please try again.";

    private final String field;

    public BreachedPasswordCheckUnavailableException(String field) {
        super(MESSAGE);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
