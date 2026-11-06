package ooo.klae.connex.backend.exceptions;

/**
 * Refuses removal of the final passkey from a currently privileged account.
 */
public class LastPasskeyRemovalForbiddenException extends BadRequestException {
    /** Stable API code for the protected passkey removal. */
    public static final String CODE = "LAST_PASSKEY_REQUIRED";

    public LastPasskeyRemovalForbiddenException() {
        super("A privileged account must keep at least one passkey");
    }

    /**
     * Creates a refusal for a narrower last-passkey rule.
     *
     * @param message the client-facing reason
     */
    protected LastPasskeyRemovalForbiddenException(String message) {
        super(message);
    }

    @Override
    public String getCode() {
        return CODE;
    }
}
