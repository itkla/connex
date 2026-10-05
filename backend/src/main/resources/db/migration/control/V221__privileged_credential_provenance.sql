-- ============================================================================
-- Passkey provenance for privileged MFA (#1534, slice 1: recording only).
--
-- A passkey enrolled while an account was unprivileged must not satisfy the
-- privileged MFA policy after a later promotion. This slice records each
-- credential's provenance; nothing reads it for authorization yet.
--
-- webauthn_credential.privileged_assurance is account-wide, operator-trusted
-- assurance. BREAK_GLASS is recorded when the operator recovery session itself
-- enrolls the replacement passkey. GRANDFATHERED is reserved for the cutover
-- backfill that switches enforcement on.
--
-- privileged_credential_attestation holds per-organization coverage:
--   FOUNDER    a passkey of the organization's founding owner;
--   INHERITED  copied to a new passkey off the credential whose step-up
--              authorized its enrollment;
--   GRANTOR    a grantor-issued attestation code redeemed with the passkey
--              (written by a later slice).
-- A direct source (FOUNDER, GRANTOR) is never replaced by INHERITED.
--
-- org_member.founder is set only when the account founds the organization and
-- is cleared by any role change another account makes for it.
-- ============================================================================

ALTER TABLE webauthn_credential
    ADD COLUMN privileged_assurance VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'Account-wide operator-trusted assurance: GRANDFATHERED or BREAK_GLASS',
    ADD COLUMN privileged_assured_at DATETIME(3) NULL
        COMMENT 'When this credential''s assurance was recorded',
    ADD CONSTRAINT chk_webauthn_credential_privileged_assurance
        CHECK (privileged_assurance IN ('GRANDFATHERED', 'BREAK_GLASS')),
    ADD CONSTRAINT chk_webauthn_credential_privileged_assured_at
        CHECK ((privileged_assurance IS NULL) = (privileged_assured_at IS NULL));

CREATE TABLE privileged_credential_attestation (
    credential_row_id INT NOT NULL COMMENT 'Attested passkey (webauthn_credential.id)',
    org_id            INT NOT NULL COMMENT 'Organization whose privileged MFA the passkey satisfies',
    source            VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
                      COMMENT 'FOUNDER, INHERITED or GRANTOR',
    attested_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                      COMMENT 'When this row was written',
    PRIMARY KEY (credential_row_id, org_id),
    CONSTRAINT fk_privileged_credential_attestation_credential
        FOREIGN KEY (credential_row_id) REFERENCES webauthn_credential(id) ON DELETE CASCADE,
    CONSTRAINT fk_privileged_credential_attestation_org
        FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE,
    CONSTRAINT chk_privileged_credential_attestation_source
        CHECK (source IN ('FOUNDER', 'INHERITED', 'GRANTOR')),
    INDEX idx_privileged_credential_attestation_org (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Per-organization privileged MFA coverage of passkeys';

ALTER TABLE org_member
    ADD COLUMN founder BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'Founded the organization; cleared by a role change another account makes',
    ALGORITHM=INSTANT;
