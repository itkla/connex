ALTER TABLE workspace_member
    ADD COLUMN membership_id BIGINT NOT NULL AUTO_INCREMENT
        COMMENT 'Stable membership-generation ID',
    ADD UNIQUE KEY uq_workspace_member_membership_id (membership_id),
    ADD UNIQUE KEY uq_workspace_member_generation (workspace_id, user_id, membership_id);

CREATE TABLE api_credential (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'API credential ID',
    workspace_id     INT NOT NULL COMMENT 'Bound workspace ID',
    organization_id  INT NOT NULL COMMENT 'Bound organization ID',
    created_by_id    INT NOT NULL COMMENT 'Creating account ID',
    membership_id    BIGINT NOT NULL COMMENT 'Membership generation that issued the credential',
    name             VARCHAR(128) NOT NULL COMMENT 'Administrative display name',
    token_hash       CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
                     COMMENT 'SHA-256 hex digest of the complete bearer token',
    token_last4      CHAR(4) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
                     COMMENT 'Non-secret display suffix',
    expires_at       DATETIME(6) NOT NULL COMMENT 'Expiry timestamp in UTC',
    last_used_at     DATETIME(6) NULL COMMENT 'Most recent successful authentication in UTC',
    revoked_at       DATETIME(6) NULL COMMENT 'Revocation timestamp in UTC',
    revoked_by_id    INT NULL
                     COMMENT 'Account that revoked the credential; account erasure deletes the referencing row, SET NULL only protects older binaries',
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                     ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_api_credential_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    CONSTRAINT fk_api_credential_organization
        FOREIGN KEY (organization_id) REFERENCES organization(id) ON DELETE CASCADE,
    CONSTRAINT fk_api_credential_creator
        FOREIGN KEY (created_by_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_api_credential_membership
        FOREIGN KEY (workspace_id, created_by_id, membership_id)
        REFERENCES workspace_member(workspace_id, user_id, membership_id) ON DELETE CASCADE,
    CONSTRAINT fk_api_credential_revoker
        FOREIGN KEY (revoked_by_id) REFERENCES app_user(id) ON DELETE SET NULL,
    CONSTRAINT chk_api_credential_name
        CHECK (CHAR_LENGTH(TRIM(name)) BETWEEN 1 AND 128),
    CONSTRAINT chk_api_credential_last4
        CHECK (CHAR_LENGTH(token_last4) = 4),
    UNIQUE KEY uq_api_credential_token_hash (token_hash),
    INDEX idx_api_credential_workspace (workspace_id, id),
    INDEX idx_api_credential_creator (created_by_id),
    INDEX idx_api_credential_membership_generation
        (workspace_id, created_by_id, membership_id),
    INDEX idx_api_credential_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Workspace-bound personal API credentials';

CREATE TABLE api_credential_scope (
    credential_id BIGINT NOT NULL COMMENT 'API credential ID',
    scope         VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
                  COMMENT 'Additive public API scope token',
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (credential_id, scope),
    CONSTRAINT fk_api_credential_scope_credential
        FOREIGN KEY (credential_id) REFERENCES api_credential(id) ON DELETE CASCADE,
    CONSTRAINT chk_api_credential_scope
        CHECK (scope IN ('crm.read', 'crm.write', 'activities.read', 'activities.write'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Scopes granted to personal API credentials';
