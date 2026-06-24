-- ============================================================================
-- Attachment becomes workspace-scoped. The owner is polymorphic (entity_type +
-- entity_id, no FK), so workspace_id is stamped from the active workspace at
-- record time and every query/facet is filtered by it. uq(workspace_id, id)
-- anchors the attachment_tag junction.
-- ============================================================================

-- Backfill pre-existing rows (pre-Flyway schema) to the first workspace before
-- enforcing the FK. Empty on a fresh DB.
ALTER TABLE attachment ADD COLUMN workspace_id INT NOT NULL DEFAULT 0 AFTER id;
UPDATE attachment SET workspace_id = (SELECT id FROM workspace ORDER BY id LIMIT 1) WHERE EXISTS (SELECT 1 FROM workspace);
ALTER TABLE attachment
    ALTER COLUMN workspace_id DROP DEFAULT,
    ADD CONSTRAINT fk_attachment_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    ADD UNIQUE KEY uq_attachment_workspace_id (workspace_id, id),
    DROP INDEX idx_attachment_entity,
    ADD INDEX idx_attachment_entity (workspace_id, entity_type, entity_id),
    ADD INDEX idx_attachment_workspace (workspace_id);
