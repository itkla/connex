-- ============================================================================
-- Grantor-issued attestation codes for privileged MFA (#1534, slice 2).
--
-- A grantor with authority over a member's role issues a one-time code, shown
-- once and delivered out of band; the member redeems it together with a passkey
-- assertion, and the signing passkey gains GRANTOR coverage in the grant's
-- organization. Nothing enforces coverage yet; the cutover that switches
-- enforcement on revokes every grant still open.
--
-- code_digest is the SHA-256 hex of 'connex-mfa-attestation:v1:<grantee id>:<code>',
-- so a code can only ever match its own grantee; the code itself is never stored.
-- workspace_id names the workspace a workspace grantor issued through and is null
-- for an organization owner's grant. failed_attempts counts refused redemptions
-- of the grantee's open grants and stops a grant at five.
--
-- Grants go with their organization, workspace, grantor or grantee. History
-- lives in the strict issue, revoke and redeem audits, and coverage a redeemed
-- grant produced survives it with a null grant_id.
-- ============================================================================

CREATE TABLE privileged_mfa_attestation_grant (
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Surrogate key',
    org_id                     INT NOT NULL COMMENT 'Organization whose privileged MFA the code attests',
    workspace_id               INT NULL COMMENT 'Workspace a workspace grantor issued through; null for an org grant',
    grantor_user_id            INT NOT NULL COMMENT 'Account that issued the code',
    grantee_user_id            INT NOT NULL COMMENT 'Account that may redeem the code',
    code_digest                CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
                               COMMENT 'SHA-256 hex of the grantee-bound code; never the code',
    expires_at                 DATETIME(6) NOT NULL COMMENT 'Redemption deadline (UTC)',
    failed_attempts            INT NOT NULL DEFAULT 0 COMMENT 'Refused redemptions counted against the open grant',
    redeemed_at                DATETIME(6) NULL COMMENT 'When the grant was redeemed (UTC)',
    redeemed_credential_row_id INT NULL COMMENT 'Passkey the redemption attested',
    revoked_at                 DATETIME(6) NULL COMMENT 'When the grant was revoked or superseded (UTC)',
    revoked_by_user_id         INT NULL COMMENT 'Account that revoked or superseded the grant',
    created_at                 DATETIME(6) NOT NULL COMMENT 'Issuance time (UTC)',
    CONSTRAINT uq_privileged_mfa_attestation_grant_digest UNIQUE (code_digest),
    CONSTRAINT fk_privileged_mfa_attestation_grant_org
        FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE,
    CONSTRAINT fk_privileged_mfa_attestation_grant_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    CONSTRAINT fk_privileged_mfa_attestation_grant_grantor
        FOREIGN KEY (grantor_user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_privileged_mfa_attestation_grant_grantee
        FOREIGN KEY (grantee_user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_privileged_mfa_attestation_grant_credential
        FOREIGN KEY (redeemed_credential_row_id) REFERENCES webauthn_credential(id) ON DELETE SET NULL,
    CONSTRAINT fk_privileged_mfa_attestation_grant_revoker
        FOREIGN KEY (revoked_by_user_id) REFERENCES app_user(id) ON DELETE SET NULL,
    CONSTRAINT chk_privileged_mfa_attestation_grant_parties
        CHECK (grantor_user_id <> grantee_user_id),
    INDEX idx_privileged_mfa_attestation_grant_grantee (grantee_user_id, org_id),
    INDEX idx_privileged_mfa_attestation_grant_org (org_id),
    INDEX idx_privileged_mfa_attestation_grant_workspace (workspace_id),
    INDEX idx_privileged_mfa_attestation_grant_grantor (grantor_user_id),
    INDEX idx_privileged_mfa_attestation_grant_credential (redeemed_credential_row_id),
    INDEX idx_privileged_mfa_attestation_grant_revoker (revoked_by_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Grantor-issued privileged MFA attestation codes';

ALTER TABLE privileged_credential_attestation
    ADD COLUMN grant_id BIGINT NULL COMMENT 'Grant whose redemption wrote a GRANTOR row',
    ADD CONSTRAINT fk_privileged_credential_attestation_grant
        FOREIGN KEY (grant_id) REFERENCES privileged_mfa_attestation_grant(id) ON DELETE SET NULL,
    ADD INDEX idx_privileged_credential_attestation_grant (grant_id);
