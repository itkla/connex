package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A grantor-issued, single-use code attesting one member's passkey for privileged MFA in one
 * organization (#1534). Only the SHA-256 digest of the grantee-bound code is persisted; the code is
 * shown to the grantor once and delivered out of band.
 */
@Data
@NoArgsConstructor
public class PrivilegedMfaAttestationGrant {
    private long id;
    private int orgId;
    private Integer workspaceId;
    private int grantorUserId;
    private int granteeUserId;
    private String codeDigest;
    private String expiresAt;
    private int failedAttempts;
    private String redeemedAt;
    private Integer redeemedCredentialRowId;
    private String revokedAt;
    private Integer revokedByUserId;
    private String createdAt;
}
