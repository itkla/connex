package ooo.klae.connex.backend.exceptions;

/**
 * Refuses a privileged account that holds passkeys but none approved in every organization where
 * it is privileged (#1534). It is confined until a grantor's approval code covers one of them.
 */
public class PrivilegedMfaAttestationRequiredException extends ForbiddenException {
    /** Stable API error code for an account whose passkeys still need approval. */
    public static final String CODE = "PRIVILEGED_MFA_ATTESTATION_REQUIRED";

    /** Creates the refusal. */
    public PrivilegedMfaAttestationRequiredException() {
        super("A passkey must be approved before this privileged account can continue");
    }

    @Override
    public String getCode() {
        return CODE;
    }
}
