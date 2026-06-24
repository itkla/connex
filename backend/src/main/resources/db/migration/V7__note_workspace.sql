-- ============================================================================
-- Note is a workspace-private leaf (nothing FKs to it). It carries its own
-- workspace_id, set from the active workspace at authoring time. Composite FKs
-- to deal/person are deferred to the constraints phase.
-- ============================================================================

-- Backfill pre-existing rows (pre-Flyway schema) to the first workspace before
-- enforcing the FK. Empty on a fresh DB.
ALTER TABLE note ADD COLUMN workspace_id INT NOT NULL DEFAULT 0 AFTER id;
UPDATE note SET workspace_id = (SELECT id FROM workspace ORDER BY id LIMIT 1) WHERE EXISTS (SELECT 1 FROM workspace);
ALTER TABLE note
    ALTER COLUMN workspace_id DROP DEFAULT,
    ADD CONSTRAINT fk_note_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    ADD INDEX idx_note_workspace (workspace_id);
