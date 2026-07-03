package ooo.klae.connex.backend.webauthn;

import java.time.Instant;

import lombok.Data;

/**
 * Persistence row for {@code webauthn_credential}: one enrolled authenticator's public
 * key, signature counter, and metadata. Translated to and from Spring Security's
 * {@code CredentialRecord} by {@link MyBatisUserCredentialRepository}.
 */
@Data
public class WebauthnCredentialRow {
    private Integer id;
    private byte[] credentialId;
    private String userEntityUserId;
    private String credentialType;
    private byte[] publicKey;
    private long signatureCount;
    private boolean uvInitialized;
    private boolean backupEligible;
    private boolean backupState;
    private String transports;
    private byte[] attestationObject;
    private byte[] attestationClientDataJson;
    private String label;
    private Instant createdAt;
    private Instant lastUsedAt;
}
