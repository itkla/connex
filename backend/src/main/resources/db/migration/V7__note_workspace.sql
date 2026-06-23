-- ============================================================================
-- Note is a workspace-private leaf (nothing FKs to it). It carries its own
-- workspace_id, set from the active workspace at authoring time. Composite FKs
-- to deal/person are deferred to the constraints phase.
-- ============================================================================

ALTER TABLE note
    ADD COLUMN workspace_id INT NOT NULL AFTER id,
    ADD CONSTRAINT fk_note_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    ADD INDEX idx_note_workspace (workspace_id);
