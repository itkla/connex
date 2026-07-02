-- ============================================================================
-- password_reset_token : single-use, expiring tokens for the forgot-password
-- flow. Not workspace-scoped — users are global, so the row keys on app_user.
-- Only the SHA-256 hash of the token is stored; the raw token travels in the
-- reset email and never touches the database. A token is redeemable while
-- consumed_at IS NULL and expires_at is in the future.
-- ============================================================================

CREATE TABLE password_reset_token (
    id           INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Reset token ID',
    user_id      INT NOT NULL COMMENT 'User the token can reset',
    token_hash   CHAR(64) NOT NULL COMMENT 'SHA-256 hex of the raw token',
    expires_at   DATETIME NOT NULL COMMENT 'Expiry timestamp (UTC)',
    consumed_at  DATETIME COMMENT 'Redemption timestamp (UTC); null while unused',
    requested_ip VARCHAR(45) COMMENT 'Requesting client IP, for abuse audit',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    CONSTRAINT fk_password_reset_token_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    UNIQUE KEY uq_password_reset_token_hash (token_hash),
    INDEX idx_password_reset_token_user (user_id),
    INDEX idx_password_reset_token_expires (expires_at)
) DEFAULT CHARSET=utf8mb4 COMMENT='Single-use password reset tokens';
