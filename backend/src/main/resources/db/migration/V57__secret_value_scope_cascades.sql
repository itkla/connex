ALTER TABLE secret_value
    ADD COLUMN workspace_id INT NULL AFTER scope_id,
    ADD COLUMN org_id INT NULL AFTER workspace_id;

UPDATE secret_value
SET workspace_id = CASE WHEN scope_type = 'workspace' THEN scope_id ELSE NULL END,
    org_id = CASE WHEN scope_type = 'organization' THEN scope_id ELSE NULL END;

ALTER TABLE secret_value
    ADD KEY idx_secret_value_workspace (workspace_id),
    ADD KEY idx_secret_value_org (org_id),
    ADD CONSTRAINT fk_secret_value_workspace
        FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_secret_value_org
        FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE,
    ADD CONSTRAINT ck_secret_value_scope_owner CHECK (
        (scope_type = 'workspace' AND workspace_id = scope_id AND org_id IS NULL)
        OR (scope_type = 'organization' AND org_id = scope_id AND workspace_id IS NULL)
        OR (scope_type = 'instance' AND scope_id = 0 AND workspace_id IS NULL AND org_id IS NULL)
    );
