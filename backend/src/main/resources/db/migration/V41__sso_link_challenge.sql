-- ============================================================================
-- sso_link_challenge : single-use, expiring tokens for the SSO account-linking
-- flow (#296 P3). When a verified IdP email collides with an existing password
-- account (SsoLoginResult.LinkRequired), the account is never auto-linked — the
-- user must prove ownership by re-entering their password once. A challenge is
-- minted at the IdP success handler and its raw token travels only in the redirect
-- to the linking screen; only the SHA-256 hash is stored, mirroring V29's
-- password_reset_token. A challenge is redeemable while consumed_at IS NULL and
-- expires_at is in the future. Keyed on app_user/organization (control-plane,
-- pre-login) — not workspace-scoped.
-- ============================================================================

CREATE TABLE sso_link_challenge (
    id               INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Challenge ID',
    token_hash       CHAR(64) NOT NULL COMMENT 'SHA-256 hex of the raw token',
    user_id          INT NOT NULL COMMENT 'Password account being linked',
    provider         VARCHAR(16) NOT NULL COMMENT 'oidc | saml',
    issuer           VARCHAR(512) NOT NULL COMMENT 'OIDC issuer URL or SAML IdP entityId',
    external_subject VARCHAR(512) NOT NULL COMMENT 'OIDC sub or SAML NameID (stable IdP subject)',
    org_id           INT NOT NULL COMMENT 'Organization whose connection minted the challenge',
    expires_at       DATETIME NOT NULL COMMENT 'Expiry timestamp (UTC)',
    consumed_at      DATETIME COMMENT 'Redemption timestamp (UTC); null while unused',
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    CONSTRAINT fk_sso_link_challenge_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_sso_link_challenge_org FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE,
    UNIQUE KEY uq_sso_link_challenge_hash (token_hash),
    INDEX idx_sso_link_challenge_user (user_id),
    INDEX idx_sso_link_challenge_expires (expires_at)
) DEFAULT CHARSET=utf8mb4 COMMENT='Single-use SSO account-linking challenges';
