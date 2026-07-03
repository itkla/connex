-- ============================================================================
-- registration_verification_token : single-use, expiring tokens that prove a
-- newly-registered account controls its email address. Mirrors email_change_token
-- (V30) but verifies the account's *own* current address rather than a pending new
-- one. Not workspace-scoped — users are global, so the row keys on app_user. Only
-- the SHA-256 hash of the token is stored; the raw token travels in the verification
-- email and never touches the database. Redeemable while consumed_at IS NULL and
-- expires_at is in the future.
-- ============================================================================

CREATE TABLE registration_verification_token (
    id           INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Registration verification token ID',
    user_id      INT NOT NULL COMMENT 'User the token verifies',
    token_hash   CHAR(64) NOT NULL COMMENT 'SHA-256 hex of the raw token',
    expires_at   DATETIME NOT NULL COMMENT 'Expiry timestamp (UTC)',
    consumed_at  DATETIME COMMENT 'Redemption timestamp (UTC); null while unused',
    requested_ip VARCHAR(45) COMMENT 'Requesting client IP, for abuse audit',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    CONSTRAINT fk_registration_verification_token_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    UNIQUE KEY uq_registration_verification_token_hash (token_hash),
    INDEX idx_registration_verification_token_user (user_id),
    INDEX idx_registration_verification_token_expires (expires_at)
) DEFAULT CHARSET=utf8mb4 COMMENT='Single-use registration email verification tokens';
