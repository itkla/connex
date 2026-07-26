ALTER TABLE sso_connection
    DROP FOREIGN KEY fk_sso_connection_workspace,
    MODIFY COLUMN jit_workspace_id INT NULL
        COMMENT 'Workspace for JIT provisioning; NULL when the configured target was removed';

ALTER TABLE sso_connection
    ADD CONSTRAINT fk_sso_connection_workspace
        FOREIGN KEY (jit_workspace_id) REFERENCES workspace(id) ON DELETE SET NULL;

ALTER TABLE organization
    ADD COLUMN lifecycle_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'active'
        COMMENT 'Tenant lifecycle fence and resumable teardown state',
    ADD CONSTRAINT chk_organization_lifecycle_state
        CHECK (lifecycle_state IN ('active', 'tearing_down'));

ALTER TABLE workspace
    ADD COLUMN lifecycle_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'active'
        COMMENT 'Tenant lifecycle fence and resumable teardown state',
    ADD CONSTRAINT chk_workspace_lifecycle_state
        CHECK (lifecycle_state IN ('active', 'tearing_down'));

CREATE TABLE tenant_operation_lease (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id INT NOT NULL,
    workspace_id INT NOT NULL,
    lease_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    teardown_workspace_id INT GENERATED ALWAYS AS (
        CASE WHEN lease_kind = 'teardown' THEN workspace_id END
    ) STORED,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_tenant_operation_lease_token (lease_token),
    UNIQUE KEY uq_tenant_operation_lease_teardown (teardown_workspace_id),
    KEY idx_tenant_operation_lease_workspace_kind (workspace_id, lease_kind),
    KEY idx_tenant_operation_lease_org_kind (org_id, lease_kind),
    CONSTRAINT fk_tenant_operation_lease_org
        FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE,
    CONSTRAINT fk_tenant_operation_lease_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    CONSTRAINT chk_tenant_operation_lease_kind
        CHECK (lease_kind IN ('export', 'teardown'))
) DEFAULT CHARSET=utf8mb4
    COMMENT='Fail-closed lifecycle operation leases without automatic expiry';
