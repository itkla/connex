-- ============================================================================
-- Tags are per-tenant (not shared). The process-global UNIQUE(name) is replaced
-- by a per-workspace UNIQUE(workspace_id, name) so one tenant's tag names can no
-- longer collide with or leak to another's.
-- ============================================================================

ALTER TABLE tag
    ADD COLUMN workspace_id INT NOT NULL AFTER id,
    DROP INDEX name,
    ADD CONSTRAINT fk_tag_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    ADD UNIQUE KEY uq_tag_workspace_name (workspace_id, name),
    ADD UNIQUE KEY uq_tag_workspace_id (workspace_id, id),
    ADD INDEX idx_tag_workspace (workspace_id);
