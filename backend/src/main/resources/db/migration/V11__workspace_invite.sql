-- ============================================================================
-- workspace_invite : email-token invitations to join a workspace. A pending
-- invite is redeemed by an authenticated user whose email matches; the token is
-- the secret. Admin-adds-existing-member is a separate flow that writes
-- workspace_member directly and needs no invite row.
-- ============================================================================

CREATE TABLE workspace_invite (
    id              INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Invite ID',
    workspace_id    INT NOT NULL COMMENT 'Workspace the invite grants access to',
    email           VARCHAR(255) NOT NULL COMMENT 'Invited email address',
    role            VARCHAR(32) NOT NULL DEFAULT 'member' COMMENT 'Role granted on accept',
    token           VARCHAR(64) NOT NULL COMMENT 'Opaque acceptance secret',
    status          VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending | accepted | revoked',
    invited_by_id   INT COMMENT 'User who created the invite',
    accepted_by_id  INT COMMENT 'User who accepted the invite',
    expires_at      DATETIME NOT NULL COMMENT 'Expiry timestamp (UTC)',
    accepted_at     DATETIME COMMENT 'Acceptance timestamp (UTC)',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_workspace_invite_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_invite_inviter FOREIGN KEY (invited_by_id) REFERENCES app_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_workspace_invite_acceptor FOREIGN KEY (accepted_by_id) REFERENCES app_user(id) ON DELETE SET NULL,
    UNIQUE KEY uq_workspace_invite_token (token),
    INDEX idx_workspace_invite_workspace (workspace_id, status),
    INDEX idx_workspace_invite_email (email)
) DEFAULT CHARSET=utf8mb4 COMMENT='Workspace email-token invitations';
