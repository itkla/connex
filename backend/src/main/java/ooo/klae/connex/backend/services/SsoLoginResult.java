package ooo.klae.connex.backend.services;

import ooo.klae.connex.backend.beans.User;

/**
 * Outcome of resolving a federated (SSO) login against Connex accounts. Either the
 * IdP identity maps to a Connex {@link User} the caller may sign in ({@link Login}),
 * or the verified IdP email collides with an existing password account that must be
 * explicitly linked before it can be used for SSO ({@link LinkRequired}) — never
 * auto-linked and never granted a session. A sealed hierarchy so callers must handle
 * both cases exhaustively.
 */
public sealed interface SsoLoginResult permits SsoLoginResult.Login, SsoLoginResult.LinkRequired {

    /** The IdP identity resolved to {@code user}; the caller may establish a session. */
    record Login(User user) implements SsoLoginResult {
    }

    /**
     * The verified IdP email matches an existing password account ({@code existingUserId})
     * that must prove ownership before the identity ({@code provider} / {@code issuer} /
     * {@code subject} within {@code orgId}) is linked. No session is established and no
     * identity row is written.
     */
    record LinkRequired(int existingUserId, String provider, String issuer, String subject, int orgId)
            implements SsoLoginResult {
    }

    /**
     * A resolution the caller may sign in.
     * @param user the resolved Connex user
     * @return a login outcome
     */
    static SsoLoginResult login(User user) {
        return new Login(user);
    }

    /**
     * A resolution that requires explicit account linking before use.
     * @param existingUserId the pre-existing password account
     * @param provider the IdP protocol
     * @param issuer the IdP issuer
     * @param subject the stable IdP subject
     * @param orgId the organization whose connection minted the identity
     * @return a link-required outcome
     */
    static SsoLoginResult linkRequired(int existingUserId, String provider, String issuer, String subject, int orgId) {
        return new LinkRequired(existingUserId, provider, issuer, subject, orgId);
    }
}
