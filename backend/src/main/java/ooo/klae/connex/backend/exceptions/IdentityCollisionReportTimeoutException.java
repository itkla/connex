package ooo.klae.connex.backend.exceptions;

/**
 * Raised when the identity-collision group report exceeds its hard availability deadline.
 */
public class IdentityCollisionReportTimeoutException extends RuntimeException {

    public static final String CODE = "IDENTITY_COLLISION_REPORT_TIMEOUT";
    public static final String MESSAGE =
        "Identity collision report timed out; narrow the filters and retry";

    public IdentityCollisionReportTimeoutException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
