package ooo.klae.connex.backend.exceptions;

/** Refuses an organization grant without disclosing whether an ineligible account exists. */
public class OrganizationMemberEligibilityRequiredException extends BadRequestException {
    /** Stable API code for this business refusal. */
    public static final String CODE = "ORG_MEMBER_ELIGIBILITY_REQUIRED";

    /** Creates the refusal with an operation-specific explanation. */
    public OrganizationMemberEligibilityRequiredException(String message) {
        super(message);
    }

    @Override
    public String getCode() {
        return CODE;
    }
}
