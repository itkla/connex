-- ============================================================================
-- Passkey / WebAuthn storage (#295). Two tables backing Spring Security's
-- PublicKeyCredentialUserEntityRepository and UserCredentialRepository:
--   webauthn_user_entity — one WebAuthn user handle per account (the stable,
--     opaque PublicKeyCredentialUserEntity.id). Carries user_id so a credential
--     resolves back to app_user durably even if the username later changes.
--   webauthn_credential — one row per enrolled authenticator (public key +
--     signature counter + metadata), linked to the account via the user handle.
-- Credentials attach to the global app_user (authentication precedes workspace
-- selection), never to a workspace. Additive to password login; password_hash
-- stays populated for these accounts.
-- ============================================================================

CREATE TABLE webauthn_user_entity (
    id            VARCHAR(128) PRIMARY KEY COMMENT 'base64url WebAuthn user handle (PublicKeyCredentialUserEntity.id)',
    user_id       INT NOT NULL COMMENT 'Owning app_user',
    name          VARCHAR(255) NOT NULL COMMENT 'WebAuthn user.name (username at enrollment)',
    display_name  VARCHAR(255) NOT NULL COMMENT 'WebAuthn user.displayName',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    CONSTRAINT fk_webauthn_user_entity_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    UNIQUE KEY uq_webauthn_user_entity_user (user_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='WebAuthn user handles, one per account';

CREATE TABLE webauthn_credential (
    id                           INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Surrogate key',
    credential_id                VARBINARY(1023) NOT NULL UNIQUE COMMENT 'Raw credential id (assertion lookup key)',
    user_entity_user_id          VARCHAR(128) NOT NULL COMMENT 'Owning user handle (webauthn_user_entity.id)',
    credential_type              VARCHAR(32) NOT NULL DEFAULT 'public-key' COMMENT 'PublicKeyCredentialType',
    public_key                   VARBINARY(1024) NOT NULL COMMENT 'COSE-encoded public key',
    signature_count              BIGINT NOT NULL DEFAULT 0 COMMENT 'Signature counter (cloned-authenticator detection)',
    uv_initialized               BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'User verification initialized',
    backup_eligible              BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Credential is backup eligible',
    backup_state                 BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Credential is currently backed up',
    transports                   VARCHAR(255) COMMENT 'Comma-separated authenticator transports',
    attestation_object           BLOB COMMENT 'Raw attestation object captured at registration',
    attestation_client_data_json BLOB COMMENT 'Raw attestation clientDataJSON captured at registration',
    label                        VARCHAR(255) COMMENT 'User-set nickname',
    created_at                   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Enrollment timestamp',
    last_used_at                 DATETIME(3) COMMENT 'Most recent successful assertion',
    CONSTRAINT fk_webauthn_credential_user_entity FOREIGN KEY (user_entity_user_id) REFERENCES webauthn_user_entity(id) ON DELETE CASCADE,
    INDEX idx_webauthn_credential_user_entity (user_entity_user_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='WebAuthn/passkey credentials';
