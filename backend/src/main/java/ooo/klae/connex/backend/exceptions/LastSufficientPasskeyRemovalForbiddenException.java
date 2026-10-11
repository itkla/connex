package ooo.klae.connex.backend.exceptions;

/**
 * Refuses removal of a privileged account's last sufficient passkey once attestation is enforced
 * (#1534). Other passkeys may remain, but none is approved in every organization where the account
 * is privileged, so the removal would confine the account.
 */
public class LastSufficientPasskeyRemovalForbiddenException extends LastPasskeyRemovalForbiddenException {
    /** Creates the refusal. */
    public LastSufficientPasskeyRemovalForbiddenException() {
        super("A privileged account must keep at least one passkey approved in every organization where it is privileged");
    }
}
