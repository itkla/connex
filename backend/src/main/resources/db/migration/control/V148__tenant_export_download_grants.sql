CREATE TABLE tenant_export_download_grant (
    token_hash BINARY(32) NOT NULL,
    session_hash BINARY(32) NOT NULL,
    org_id INT NOT NULL,
    workspace_id INT NOT NULL,
    actor_id INT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (token_hash),
    UNIQUE KEY uq_tenant_export_grant_binding
        (actor_id, session_hash, org_id, workspace_id),
    KEY idx_tenant_export_grant_expiry (expires_at),
    CONSTRAINT fk_tenant_export_grant_org
        FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE,
    CONSTRAINT fk_tenant_export_grant_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    CONSTRAINT fk_tenant_export_grant_actor
        FOREIGN KEY (actor_id) REFERENCES app_user(id) ON DELETE CASCADE
);
