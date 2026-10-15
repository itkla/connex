package ooo.klae.connex.backend.exceptions;

/** Refuses a change that would remove the last organization owner. */
public class OrganizationOwnerRequiredException extends BadRequestException {
    /** Stable API error code for a change that would leave the organization without an owner. */
    public static final String CODE = "ORG_LAST_OWNER";

    /**
     * Creates the refusal.
     *
     * @param message the refusal's message
     */
    public OrganizationOwnerRequiredException(String message) {
        super(message);
    }

    /** Returns the stable refusal code. */
    public String getCode() {
        return CODE;
    }
}
