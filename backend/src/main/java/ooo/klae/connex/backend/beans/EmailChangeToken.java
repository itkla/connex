package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single-use verified email-change token. Only the SHA-256 hash of the raw
 * token is persisted; the raw token is delivered to the pending new address by
 * email and is never stored. The pending {@code newEmail} is applied to the
 * account only when the token is redeemed.
 */
@Data
@NoArgsConstructor
public class EmailChangeToken {
    private int id;
    private int userId;
    private String newEmail;
    private String tokenHash;
    private String expiresAt;
    private String consumedAt;
    private String requestedIp;
    private String createdAt;
}
