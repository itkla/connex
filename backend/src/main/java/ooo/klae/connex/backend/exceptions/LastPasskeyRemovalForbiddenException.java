package ooo.klae.connex.backend.exceptions;

/**
 * Refuses removal of the final passkey from a currently privileged account.
 */
public class LastPasskeyRemovalForbiddenException extends BadRequestException {
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
}
