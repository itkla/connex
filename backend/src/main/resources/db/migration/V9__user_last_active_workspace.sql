-- ============================================================================
-- Remembers each user's last active workspace so the resolver can restore it on
-- the next login. NULL until a workspace is selected; cleared if that workspace
-- is deleted.
-- ============================================================================

ALTER TABLE app_user
    ADD COLUMN last_active_workspace_id INT NULL AFTER timezone,
    ADD CONSTRAINT fk_app_user_last_active_workspace
        FOREIGN KEY (last_active_workspace_id) REFERENCES workspace(id) ON DELETE SET NULL;
