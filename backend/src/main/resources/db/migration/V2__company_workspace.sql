-- ============================================================================
-- Company becomes workspace-owned. Per the sharing model (plan §0.1) a company
-- is owned by one workspace and may be shared with others via company_share;
-- references into company therefore use plain FKs + an app visibility check,
-- while the uq(workspace_id, id) anchor is kept for same-workspace composite FKs.
-- ============================================================================

ALTER TABLE company
    ADD COLUMN workspace_id INT NOT NULL AFTER id,
    ADD CONSTRAINT fk_company_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    ADD UNIQUE KEY uq_company_workspace_id (workspace_id, id),
    ADD INDEX idx_company_workspace (workspace_id);

-- Cross-workspace shares of a company; the owner remains company.workspace_id.
CREATE TABLE company_share (
    company_id   INT NOT NULL COMMENT 'Shared company ID',
    workspace_id INT NOT NULL COMMENT 'Workspace the company is shared with',
    granted_by   INT NULL COMMENT 'User who granted the share',
    can_edit     BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Whether the grantee workspace may edit',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Share creation timestamp',
    PRIMARY KEY (company_id, workspace_id),
    CONSTRAINT fk_company_share_company    FOREIGN KEY (company_id)   REFERENCES company(id)   ON DELETE CASCADE,
    CONSTRAINT fk_company_share_workspace  FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    CONSTRAINT fk_company_share_granted_by FOREIGN KEY (granted_by)   REFERENCES app_user(id)  ON DELETE SET NULL,
    INDEX idx_company_share_workspace (workspace_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Cross-workspace company shares';
