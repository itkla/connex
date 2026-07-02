package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single-use password reset token. Only the SHA-256 hash of the raw token is
 * persisted; the raw token is delivered to the user by email and is never stored.
 */
@Data
@NoArgsConstructor
public class PasswordResetToken {
    private int id;
    private int userId;
    private String tokenHash;
    private String expiresAt;
    private String consumedAt;
    private String requestedIp;
    private String createdAt;
}
