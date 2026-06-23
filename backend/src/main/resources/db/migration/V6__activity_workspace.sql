-- ============================================================================
-- Activity is a workspace-private leaf (nothing FKs to it). It carries its own
-- workspace_id, set from the active workspace at log time. Composite FKs to
-- deal/person are deferred to the constraints phase.
-- ============================================================================

ALTER TABLE activity
    ADD COLUMN workspace_id INT NOT NULL AFTER id,
    ADD CONSTRAINT fk_activity_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    ADD INDEX idx_activity_workspace (workspace_id);
