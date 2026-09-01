-- ============================================================================
-- Out-of-band confirmation for a FIRST passkey on a privileged account (#1506).
--
-- PrivilegedMfaEnforcementFilter confines an unenrolled privileged account to the
-- enrollment endpoints, so enrollment is the only reachable door. Bootstrap
-- authorization reduced to a current-password check, which made a stolen password
-- sufficient to enroll an attacker-controlled passkey and receive a step-up stamp.
--
-- Each row is a single-use, short-lived confirmation delivered by email to the
-- account's own address. Only the SHA-256 digest is persisted; the raw bearer
-- travels in a URL fragment (CHK-050) and is never stored, logged, or audited.
--
-- session_primary_id binds a confirmation to the exact session that requested it.
-- Without that binding an attacker holding the password could request a
-- confirmation and have the legitimate owner redeem it, authorizing the attacker's
-- own enrollment. Redemption therefore requires both the emailed bearer and the
-- requesting session. Spring Session rotates SESSION_ID on authentication while
-- keeping one physical row, so the immutable PRIMARY_ID is what the binding names.
-- ============================================================================

CREATE TABLE passkey_bootstrap_confirmation_token (
    id           INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Confirmation token ID',
    user_id      INT NOT NULL COMMENT 'Account the confirmation authorizes',
    token_hash   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
                 COMMENT 'SHA-256 hex digest of the raw bearer',
    session_primary_id CHAR(36) NOT NULL COMMENT 'Requesting SPRING_SESSION.PRIMARY_ID, required at redemption',
    expires_at   DATETIME NOT NULL COMMENT 'Expiry timestamp (UTC)',
    consumed_at  DATETIME NULL COMMENT 'Redemption timestamp (UTC); null while unused',
    requested_ip VARCHAR(45) NULL COMMENT 'Requesting client IP, for abuse audit',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    CONSTRAINT fk_passkey_bootstrap_confirmation_user
        FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    UNIQUE KEY uq_passkey_bootstrap_confirmation_hash (token_hash),
    INDEX idx_passkey_bootstrap_confirmation_user (user_id),
    INDEX idx_passkey_bootstrap_confirmation_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Single-use first-passkey enrollment confirmations';
