package ooo.klae.connex.backend.exceptions;

/** Refuses disclosure until identity verification has been recorded. */
public class DisclosureIdentityVerificationRequiredException extends BadRequestException {
    /** Stable API code for this business refusal. */
    public static final String CODE = "DISCLOSURE_IDENTITY_VERIFICATION_REQUIRED";

    /** Creates the refusal with an operation-specific explanation. */
    public DisclosureIdentityVerificationRequiredException(String message) {
        super(message);
    }

    @Override
    public String getCode() {
        return CODE;
    }
}
