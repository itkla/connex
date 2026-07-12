package ooo.klae.connex.backend.exceptions;

/**
 * Signals that WebAuthn step-up cannot begin until the account enrolls a passkey.
 */
public class PasskeyEnrollmentRequiredException extends BadRequestException {
    public static final String CODE = "PASSKEY_ENROLLMENT_REQUIRED";

    public PasskeyEnrollmentRequiredException() {
        super("A passkey must be enrolled before this action can be completed");
    }

    public String getCode() {
        return CODE;
    }
}
