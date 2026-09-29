-- ============================================================================
-- Single-use ledger for operator break-glass recovery tokens (#1532).
--
-- The operator configures only a SHA-256 digest of the raw token, computed over
-- 'connex-privileged-mfa-recovery:v1:<user id>:<token>', so a token is valid for
-- exactly one account. A row here records that the configured digest has been
-- redeemed, which stops the same token being replayed for the rest of its window,
-- across a restart, or on another backend replica.
--
-- token_digest is the SHA-256 of the configured digest. It is never the raw token
-- and never the configured digest itself, so a row cannot be matched against the
-- environment value directly.
--
-- MfaRecoveryService inserts the row inside the recovery transaction, while holding
-- the account's app_user row exclusively and before any credential is removed. A
-- ceremony that fails afterwards rolls the row back and leaves the token unspent.
-- ============================================================================

CREATE TABLE privileged_mfa_recovery_redemption (
    token_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
                 COMMENT 'SHA-256 hex of the configured recovery token digest',
    user_id      INT NOT NULL COMMENT 'Account that redeemed the token',
    operator     VARCHAR(255) NOT NULL COMMENT 'Configured operator actor who issued the token',
    redeemed_at  DATETIME(6) NOT NULL COMMENT 'Redemption timestamp (UTC)',
    PRIMARY KEY (token_digest),
    CONSTRAINT fk_privileged_mfa_recovery_redemption_user
        FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    INDEX idx_privileged_mfa_recovery_redemption_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Single-use ledger of redeemed privileged MFA recovery tokens';
