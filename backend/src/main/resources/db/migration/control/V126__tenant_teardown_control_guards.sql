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

DROP TRIGGER trg_audit_log_no_update;

ALTER TABLE audit_log
    ADD COLUMN integrity_workspace_id INT NULL
        COMMENT 'Immutable workspace reference included in the row HMAC',
    ADD COLUMN integrity_org_id INT NULL
        COMMENT 'Immutable organization reference included in the row HMAC',
    ADD COLUMN integrity_actor_id INT NULL
        COMMENT 'Immutable actor reference included in the row HMAC',
    ADD COLUMN integrity_reference_state VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'captured'
        COMMENT 'Whether immutable HMAC identifiers remain recoverable after migration',
    ADD CONSTRAINT chk_audit_log_integrity_reference_state
        CHECK (integrity_reference_state IN ('captured', 'legacy_unknown'));

CREATE TRIGGER trg_audit_log_integrity_snapshot BEFORE INSERT ON audit_log
FOR EACH ROW SET
    NEW.integrity_workspace_id = NEW.workspace_id,
    NEW.integrity_org_id = NEW.org_id,
    NEW.integrity_actor_id = NEW.actor_id,
    NEW.integrity_reference_state = 'captured';

UPDATE audit_log
SET integrity_workspace_id = workspace_id,
    integrity_org_id = org_id,
    integrity_actor_id = actor_id,
    integrity_reference_state = CASE
        WHEN row_hash IS NOT NULL
          AND (
            (chain_scope_type = 'workspace' AND workspace_id IS NULL)
            OR (chain_scope_type = 'organization' AND org_id IS NULL)
            OR (actor_id IS NULL AND actor_label IS NOT NULL)
          )
        THEN 'legacy_unknown'
        ELSE 'captured'
    END;

CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE ON audit_log
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';

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
    CONSTRAINT chk_tenant_operation_lease_kind
        CHECK (lease_kind IN ('export', 'teardown'))
) DEFAULT CHARSET=utf8mb4
    COMMENT='Fail-closed lifecycle operation leases without automatic expiry';

CREATE TABLE tenant_cleanup_tombstone (
    workspace_id INT NOT NULL,
    org_id INT NOT NULL,
    workspace_name VARCHAR(255) NOT NULL,
    workspace_slug VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id),
    KEY idx_tenant_cleanup_tombstone_org (org_id, workspace_id),
    CONSTRAINT fk_tenant_cleanup_tombstone_org
        FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE
) DEFAULT CHARSET=utf8mb4
    COMMENT='Root-independent route marker for post-workspace tenant cleanup';
