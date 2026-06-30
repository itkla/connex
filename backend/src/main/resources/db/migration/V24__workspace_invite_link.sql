-- ============================================================================
-- workspace_invite_link : shareable, owner-issued link tokens for joining a
-- workspace. Unlike workspace_invite (single email-bound, single-use), a link is
-- not bound to an email and may be redeemed up to max_uses times until it expires
-- or is revoked. Redemptions are tracked per user for idempotency and audit.
-- (V23 is reserved for the in-flight bulk-operations branch.)
-- ============================================================================

CREATE TABLE workspace_invite_link (
    id              INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Invite link ID',
    workspace_id    INT NOT NULL COMMENT 'Workspace the link grants access to',
    token           VARCHAR(64) NOT NULL COMMENT 'Opaque redemption secret',
    role            VARCHAR(32) NOT NULL DEFAULT 'member' COMMENT 'Role granted on redeem',
    expires_at      DATETIME NULL COMMENT 'Expiry timestamp (UTC); NULL = never expires',
    max_uses        INT NULL COMMENT 'Maximum redemptions; NULL = unlimited',
    used_count      INT NOT NULL DEFAULT 0 COMMENT 'Successful redemptions so far',
    revoked         TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Whether the link has been revoked',
    created_by_id   INT COMMENT 'User who created the link',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_workspace_invite_link_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_invite_link_creator FOREIGN KEY (created_by_id) REFERENCES app_user(id) ON DELETE SET NULL,
    UNIQUE KEY uq_workspace_invite_link_token (token),
    INDEX idx_workspace_invite_link_workspace (workspace_id, revoked)
) DEFAULT CHARSET=utf8mb4 COMMENT='Shareable workspace invite links';

CREATE TABLE workspace_invite_link_redemption (
    link_id         INT NOT NULL COMMENT 'Redeemed link',
    user_id         INT NOT NULL COMMENT 'User who redeemed it',
    redeemed_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Redemption timestamp',
    PRIMARY KEY (link_id, user_id),
    CONSTRAINT fk_invite_link_redemption_link FOREIGN KEY (link_id) REFERENCES workspace_invite_link(id) ON DELETE CASCADE,
    CONSTRAINT fk_invite_link_redemption_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
) DEFAULT CHARSET=utf8mb4 COMMENT='Per-user redemptions of workspace invite links';
