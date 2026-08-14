package ooo.klae.connex.backend.services;

import ooo.klae.connex.backend.beans.User;

/**
 * Delivers an email-change verification link to a pending new address. The raw
 * token is passed here only — it is never persisted — and callers must treat it
 * as a bearer secret. The default {@link LoggingEmailChangeEmailService} records
 * only that delivery is unavailable; real delivery requires
 * {@code connex.email-change.email-enabled} and a usable instance SMTP transport.
 */
public interface EmailChangeEmailService {

    /**
     * Sends a verification link for the given user to the pending new address.
     * @param user the account requesting the change
     * @param newEmail the pending new address the link is sent to
     * @param rawToken the unhashed token to embed in the link
     */
    void sendVerificationEmail(User user, String newEmail, String rawToken);
}
