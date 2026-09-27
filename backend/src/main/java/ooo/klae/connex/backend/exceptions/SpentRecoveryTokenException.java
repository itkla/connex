package ooo.klae.connex.backend.exceptions;

import ooo.klae.connex.backend.config.PrivilegedMfaProperties;

/**
 * Refuses a break-glass recovery token that has already been redeemed (#1532).
 *
 * <p>The client sees exactly the refusal an invalid token gets: the same status, code and message,
 * so a caller cannot tell a spent token from a wrong one. The subtype exists only so the server can
 * audit the replay distinctly, because reaching it proves the caller holds both the account's first
 * factor and a token that was valid for that account.
 */
public class SpentRecoveryTokenException extends ForbiddenException {
    public SpentRecoveryTokenException() {
        super(PrivilegedMfaProperties.INVALID_RECOVERY_AUTHORIZATION);
    }
}
