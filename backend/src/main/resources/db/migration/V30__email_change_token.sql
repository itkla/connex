-- ============================================================================
-- email_change_token : single-use, expiring tokens for the verified account
-- email-change flow. Not workspace-scoped — users are global, so the row keys
-- on app_user. Only the SHA-256 hash of the token is stored; the raw token
-- travels in the verification email sent to the *new* address and never touches
-- the database. A token is redeemable while consumed_at IS NULL and expires_at
-- is in the future. The requested new address is held here until the recipient
-- proves control of it by redeeming the token.
-- ============================================================================

CREATE TABLE email_change_token (
    id           INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Email change token ID',
    user_id      INT NOT NULL COMMENT 'User whose email the token changes',
    new_email    VARCHAR(255) NOT NULL COMMENT 'Pending new email address to apply on confirm',
    token_hash   CHAR(64) NOT NULL COMMENT 'SHA-256 hex of the raw token',
    expires_at   DATETIME NOT NULL COMMENT 'Expiry timestamp (UTC)',
    consumed_at  DATETIME COMMENT 'Redemption timestamp (UTC); null while unused',
    requested_ip VARCHAR(45) COMMENT 'Requesting client IP, for abuse audit',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    CONSTRAINT fk_email_change_token_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    UNIQUE KEY uq_email_change_token_hash (token_hash),
    INDEX idx_email_change_token_user (user_id),
    INDEX idx_email_change_token_expires (expires_at)
) DEFAULT CHARSET=utf8mb4 COMMENT='Single-use verified email-change tokens';
