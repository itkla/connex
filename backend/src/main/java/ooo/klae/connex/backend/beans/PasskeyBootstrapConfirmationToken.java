package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single-use confirmation authorizing the first passkey enrollment on a privileged account.
 * Only the SHA-256 digest of the raw bearer is persisted; the bearer itself is delivered by
 * email and is never stored. The row also names the session that requested it, so a
 * confirmation redeemed elsewhere cannot authorize an enrollment.
 */
@Data
@NoArgsConstructor
public class PasskeyBootstrapConfirmationToken {
    private int id;
    private int userId;
    private String tokenHash;
    private String sessionPrimaryId;
    private String expiresAt;
    private String consumedAt;
    private String requestedIp;
    private String createdAt;
}
