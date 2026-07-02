-- ============================================================================
-- workspace_mail_config : a workspace's own SMTP transport, overriding the
-- instance-wide default (CONNEX_MAIL_*) for workspace-scoped mail such as
-- invites. Owner/admin managed (WORKSPACE_SETTINGS). One row per workspace.
-- The SMTP password is stored ENCRYPTED (AES/GCM, key from CONNEX_MAIL_SECRET_KEY)
-- as a Base64 blob in password_enc; the raw password never touches the database
-- and is never returned by the API. A workspace with no row, or enabled=false,
-- uses the instance default sender.
-- ============================================================================

CREATE TABLE workspace_mail_config (
    workspace_id INT NOT NULL COMMENT 'Workspace this SMTP config belongs to',
    enabled      BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'When false, the workspace uses the instance default sender',
    host         VARCHAR(255) COMMENT 'SMTP host',
    port         INT COMMENT 'SMTP port',
    username     VARCHAR(255) COMMENT 'SMTP auth username',
    password_enc VARCHAR(2048) COMMENT 'AES/GCM-encrypted SMTP password, Base64 (iv:ciphertext); never plaintext',
    from_address VARCHAR(320) COMMENT 'Envelope/from address',
    from_name    VARCHAR(255) COMMENT 'Display name for the from address',
    starttls     BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'Use STARTTLS',
    ssl          BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Use implicit SSL/TLS',
    auth         BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'SMTP AUTH required',
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    PRIMARY KEY (workspace_id),
    CONSTRAINT fk_workspace_mail_config_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
) DEFAULT CHARSET=utf8mb4 COMMENT='Per-workspace SMTP transport override';
