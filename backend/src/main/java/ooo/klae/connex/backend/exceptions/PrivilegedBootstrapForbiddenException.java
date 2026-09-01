package ooo.klae.connex.backend.exceptions;

/**
 * Signals that first-passkey enrollment was refused because the account administers other
 * principals and offered only a password as proof.
 */
public class PrivilegedBootstrapForbiddenException extends ForbiddenException {
    /** Stable API error code distinguishing this refusal from an ordinary authorization failure. */
    public static final String CODE = "PRIVILEGED_PASSKEY_BOOTSTRAP_FORBIDDEN";

    public PrivilegedBootstrapForbiddenException(String message) {
        super(message);
    }

    @Override
    public String getCode() {
        return CODE;
    }
}
