package ooo.klae.connex.backend.exceptions;

/** Signals that a contact's privacy hold blocks creating a new cross-workspace share. */
public class ShareBlockedPrivacyHoldException extends BadRequestException {
    /** Stable API error code for privacy-hold share refusals. */
    public static final String CODE = "SHARE_BLOCKED_PRIVACY_HOLD";

    /** Creates the stable refusal used when a contact has a privacy hold. */
    public ShareBlockedPrivacyHoldException() {
        super("This contact asked not to be shared outside this workspace, so new shares are blocked.");
    }

    /** Returns the stable API error code for this failure. */
    @Override
    public String getCode() {
        return CODE;
    }
}
