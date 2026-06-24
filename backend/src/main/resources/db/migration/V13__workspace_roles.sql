-- ============================================================================
-- Owner-defined custom roles. A member keeps a built-in role string and may also
-- carry a custom role_id; when set, the custom role's granted permissions take
-- over. Deleting a custom role nulls the reference (members fall back to their
-- built-in role), so removal is non-destructive.
-- ============================================================================

CREATE TABLE workspace_role (
    id           INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Custom role ID',
    workspace_id INT NOT NULL COMMENT 'Owning workspace',
    name         VARCHAR(64) NOT NULL COMMENT 'Role name',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_workspace_role_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    UNIQUE KEY uq_workspace_role_name (workspace_id, name)
) DEFAULT CHARSET=utf8mb4 COMMENT='Owner-defined custom roles';

CREATE TABLE workspace_role_permission (
    workspace_role_id INT NOT NULL COMMENT 'Custom role',
    permission        VARCHAR(48) NOT NULL COMMENT 'Permission catalog key',
    PRIMARY KEY (workspace_role_id, permission),
    CONSTRAINT fk_workspace_role_permission_role FOREIGN KEY (workspace_role_id)
        REFERENCES workspace_role(id) ON DELETE CASCADE
) DEFAULT CHARSET=utf8mb4 COMMENT='Permissions granted by a custom role';

ALTER TABLE workspace_member
    ADD COLUMN role_id INT NULL AFTER role,
    ADD CONSTRAINT fk_workspace_member_role FOREIGN KEY (role_id)
        REFERENCES workspace_role(id) ON DELETE SET NULL;
