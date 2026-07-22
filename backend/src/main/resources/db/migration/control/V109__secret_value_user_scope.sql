-- Adds a per-user scope to the central secret store (#60 WS1, #665). Per-user provider tokens
-- cannot reuse the workspace/organization scopes: uq_secret_value_scope_purpose allows one secret
-- per (scope, purpose), so a second user's token would overwrite the first's. User-scoped secrets
-- cascade with the owning app_user row, mirroring the workspace/org cascades from V57.
ALTER TABLE secret_value
    ADD COLUMN user_id INT NULL AFTER org_id,
    ADD KEY idx_secret_value_user (user_id),
    ADD CONSTRAINT fk_secret_value_user
        FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;

ALTER TABLE secret_value
    DROP CONSTRAINT ck_secret_value_scope_owner,
    ADD CONSTRAINT ck_secret_value_scope_owner CHECK (
        (scope_type = 'workspace' AND workspace_id = scope_id AND org_id IS NULL AND user_id IS NULL)
        OR (scope_type = 'organization' AND org_id = scope_id AND workspace_id IS NULL AND user_id IS NULL)
        OR (scope_type = 'user' AND user_id = scope_id AND workspace_id IS NULL AND org_id IS NULL)
        OR (scope_type = 'instance' AND scope_id = 0 AND workspace_id IS NULL AND org_id IS NULL AND user_id IS NULL)
    );
