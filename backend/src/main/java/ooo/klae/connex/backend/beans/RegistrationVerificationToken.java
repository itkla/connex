package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single-use registration email-verification token. Only the SHA-256 hash of the
 * raw token is persisted; the raw token is delivered to the account's own email
 * address and is never stored. Redeeming it marks the account's email verified.
 */
@Data
@NoArgsConstructor
public class RegistrationVerificationToken {
    private int id;
    private int userId;
    private String tokenHash;
    private String expiresAt;
    private String consumedAt;
    private String requestedIp;
    private String createdAt;
}
