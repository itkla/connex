-- Per-user external provider connections (#60 WS1, #665): Google/Microsoft accounts a user has
-- connected for future mail/calendar capture. A connection belongs to a user, not a workspace —
-- workspace capture policy arrives with the sync workstreams. Tokens live in the secret store
-- (user scope, V109); this table holds only the opaque reference and non-secret metadata.
CREATE TABLE provider_connection (
    id                     INT AUTO_INCREMENT PRIMARY KEY,
    user_id                INT NOT NULL COMMENT 'Owning user; connections are self-managed',
    provider               VARCHAR(16) NOT NULL,
    status                 VARCHAR(16) NOT NULL DEFAULT 'connected',
    provider_account_email VARCHAR(255) NULL COMMENT 'Display-only account identity reported by the provider',
    granted_scopes         VARCHAR(1024) NULL COMMENT 'Space-delimited scopes granted at consent',
    credential_ref         VARCHAR(255) NULL COMMENT 'Opaque secret-store reference to the token bundle; never plaintext',
    last_sync_at           DATETIME NULL COMMENT 'Set by the future sync workstreams; NULL until then',
    error_code             VARCHAR(64) NULL COMMENT 'Machine-readable reason when status = error',
    created_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_provider_connection_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT chk_provider_connection_provider CHECK (provider IN ('google', 'microsoft')),
    CONSTRAINT chk_provider_connection_status CHECK (status IN ('connected', 'paused', 'error', 'revoked')),
    UNIQUE KEY uq_provider_connection_user_provider (user_id, provider)
) DEFAULT CHARSET=utf8mb4 COMMENT='Per-user OAuth connections to external mail/calendar providers';
