-- ============================================================================
-- Tags are per-tenant (not shared). The process-global UNIQUE(name) is replaced
-- by a per-workspace UNIQUE(workspace_id, name) so one tenant's tag names can no
-- longer collide with or leak to another's.
-- ============================================================================

-- Backfill pre-existing rows (pre-Flyway schema) to the first workspace before
-- enforcing the FK. Empty on a fresh DB.
ALTER TABLE tag ADD COLUMN workspace_id INT NOT NULL DEFAULT 0 AFTER id;
UPDATE tag SET workspace_id = (SELECT id FROM workspace ORDER BY id LIMIT 1) WHERE EXISTS (SELECT 1 FROM workspace);
ALTER TABLE tag
    ALTER COLUMN workspace_id DROP DEFAULT,
    DROP INDEX name,
    ADD CONSTRAINT fk_tag_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    ADD UNIQUE KEY uq_tag_workspace_name (workspace_id, name),
    ADD UNIQUE KEY uq_tag_workspace_id (workspace_id, id),
    ADD INDEX idx_tag_workspace (workspace_id);
