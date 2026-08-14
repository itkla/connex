package ooo.klae.connex.backend.services;

import ooo.klae.connex.backend.beans.User;

/**
 * Delivers a password reset link to a user. The raw token is passed here only —
 * it is never persisted — and callers must treat it as a bearer secret.
 * The default {@link LoggingPasswordResetEmailService} records only that delivery
 * is unavailable; real delivery requires {@code connex.password-reset.email-enabled}
 * and a usable instance SMTP transport.
 */
public interface PasswordResetEmailService {

    /**
     * Sends a reset link for the given user carrying the given raw token.
     * @param user the account being reset
     * @param rawToken the unhashed reset token to embed in the link
     */
    void sendResetEmail(User user, String rawToken);
}
