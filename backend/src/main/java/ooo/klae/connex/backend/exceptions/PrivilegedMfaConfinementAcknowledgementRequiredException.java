package ooo.klae.connex.backend.exceptions;

/**
 * Refuses a role edit that would leave members privileged in an organization without a passkey
 * approved there, until the request acknowledges it (#1534). Those members are confined, or stay
 * confined, until the organization approves one of their passkeys. The count is computed from this
 * organization's own facts only, so it reveals nothing about any other organization.
 */
public class PrivilegedMfaConfinementAcknowledgementRequiredException extends RuntimeException {
    /** Stable API error code for an edit that needs the confinement acknowledged. */
    public static final String CODE = "PRIVILEGED_MFA_CONFINEMENT_ACKNOWLEDGEMENT_REQUIRED";

    private final int affectedCount;

    /**
     * Creates the refusal.
     *
     * @param affectedCount how many members the edit leaves without a passkey approved here
     */
    public PrivilegedMfaConfinementAcknowledgementRequiredException(int affectedCount) {
        super("This change leaves members without a passkey approved in this organization;"
                + " they will be confined until one is approved");
        this.affectedCount = affectedCount;
    }

    /** @return the stable API error code */
    public String getCode() {
        return CODE;
    }

    /** @return how many members the edit leaves without a passkey approved here */
    public int getAffectedCount() {
        return affectedCount;
    }
}
