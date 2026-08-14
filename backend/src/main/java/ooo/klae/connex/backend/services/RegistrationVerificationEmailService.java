package ooo.klae.connex.backend.services;

import ooo.klae.connex.backend.beans.User;

/**
 * Delivers a registration email-verification link to a newly-registered account's
 * own address. The raw token is passed here only — it is never persisted — and
 * callers must treat it as a bearer secret. The default
 * {@link LoggingRegistrationVerificationEmailService} records only that delivery
 * is unavailable; real delivery requires
 * {@code connex.registration-verification.email-enabled} and a usable instance
 * SMTP transport.
 */
public interface RegistrationVerificationEmailService {

    /**
     * Sends a verification link for the given user to their account email.
     * @param user the account to verify
     * @param rawToken the unhashed token to embed in the link
     */
    void sendVerificationEmail(User user, String rawToken);
}
