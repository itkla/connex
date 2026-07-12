package ooo.klae.connex.backend.exceptions;

/**
 * Signals that the caller must complete the WebAuthn step-up ceremony before retrying.
 */
public class RecentAuthenticationRequiredException extends ForbiddenException {
    public static final String CODE = "RECENT_AUTHENTICATION_REQUIRED";

    public RecentAuthenticationRequiredException() {
        super("Recent WebAuthn authentication required");
    }

    public String getCode() {
        return CODE;
    }
}
