package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single-use SSO account-linking challenge. Bridges an IdP identity that
 * collided with an existing password account to a one-time password confirmation:
 * the user re-enters their password to prove ownership before the identity is
 * linked. Only the SHA-256 hash of the raw token is persisted; the raw token
 * travels in the redirect to the linking screen and is never stored.
 */
@Data
@NoArgsConstructor
public class SsoLinkChallenge {
    private int id;
    private String tokenHash;
    private int userId;
    private String provider;
    private String issuer;
    private String externalSubject;
    private Integer orgId;
    private String expiresAt;
    private String consumedAt;
    private String createdAt;
}
