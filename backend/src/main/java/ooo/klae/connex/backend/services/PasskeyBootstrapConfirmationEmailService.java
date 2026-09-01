package ooo.klae.connex.backend.services;

import ooo.klae.connex.backend.beans.User;

/**
 * Delivers a first-passkey enrollment confirmation link to the account's own address. The raw
 * bearer is passed here only — it is never persisted — and callers must treat it as a secret.
 * The default {@link LoggingPasskeyBootstrapConfirmationEmailService} records only that delivery
 * is unavailable; real delivery requires
 * {@code connex.security.privileged-mfa.bootstrap-confirmation.email-enabled} and a usable
 * instance SMTP transport.
 */
public interface PasskeyBootstrapConfirmationEmailService {

    /**
     * Sends the confirmation link for the given account to its own email address.
     * @param user the account enrolling its first passkey
     * @param rawToken the unhashed bearer to embed in the link fragment
     */
    void sendConfirmationEmail(User user, String rawToken);

    /**
     * Whether this instance can actually deliver a confirmation right now. Enrollment fails
     * closed when it cannot, so the caller is told the cause instead of waiting for mail that
     * will never arrive.
     * @return true when a real transport is configured and usable
     */
    boolean canDeliver();
}
