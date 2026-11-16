package ooo.klae.connex.backend.exceptions;

/** Refuses an invitation whose email domain is outside the applicable allowlists. */
public class InviteDomainNotAllowedException extends ForbiddenException {
    /** Stable API code for this business refusal. */
    public static final String CODE = "INVITE_EMAIL_DOMAIN_NOT_ALLOWED";

    /** Creates the refusal with an operation-specific explanation. */
    public InviteDomainNotAllowedException(String message) {
        super(message);
    }

    @Override
    public String getCode() {
        return CODE;
    }
}
