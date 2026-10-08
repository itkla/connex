package ooo.klae.connex.backend.exceptions;

/**
 * The single refusal every privileged MFA attestation redemption answers with when the code or its
 * grant cannot be used (#1534): a wrong, expired, revoked, used or exhausted code, a grantor who no
 * longer holds the authority, or a grantee who is no longer a member. It never says which.
 */
public class MfaAttestationRefusedException extends ForbiddenException {
    /** Stable API error code for a refused attestation code. */
    public static final String CODE = "MFA_ATTESTATION_REFUSED";

    /** Creates the uniform refusal. */
    public MfaAttestationRefusedException() {
        super("That attestation code cannot be used");
    }

    @Override
    public String getCode() {
        return CODE;
    }
}
