package ooo.klae.connex.backend.exceptions;

/** Refuses an organization exit when the caller is not a member. */
public class OrganizationMembershipRequiredException extends ResourceNotFoundException {
    /** Stable API error code for leaving an organization the caller does not belong to. */
    public static final String CODE = "ORG_NOT_MEMBER";

    /**
     * Creates the refusal.
     *
     * @param message the refusal's message
     */
    public OrganizationMembershipRequiredException(String message) {
        super(message);
    }

    /** Returns the stable refusal code. */
    public String getCode() {
        return CODE;
    }
}
