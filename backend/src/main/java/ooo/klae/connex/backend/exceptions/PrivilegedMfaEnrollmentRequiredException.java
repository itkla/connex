package ooo.klae.connex.backend.exceptions;

/** Refuses a privileged account until it enrolls a passkey. */
public class PrivilegedMfaEnrollmentRequiredException extends ForbiddenException {
    /** Stable API error code shared with the confinement filters. */
    public static final String CODE = "PRIVILEGED_MFA_ENROLLMENT_REQUIRED";

    /** Creates the confinement refusal. */
    public PrivilegedMfaEnrollmentRequiredException() {
        super("A passkey must be enrolled before this privileged account can continue");
    }

    @Override
    public String getCode() {
        return CODE;
    }
}
