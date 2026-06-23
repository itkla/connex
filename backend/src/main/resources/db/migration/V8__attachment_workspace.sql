-- ============================================================================
-- Attachment becomes workspace-scoped. The owner is polymorphic (entity_type +
-- entity_id, no FK), so workspace_id is stamped from the active workspace at
-- record time and every query/facet is filtered by it. uq(workspace_id, id)
-- anchors the attachment_tag junction.
-- ============================================================================

ALTER TABLE attachment
    ADD COLUMN workspace_id INT NOT NULL AFTER id,
    ADD CONSTRAINT fk_attachment_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    ADD UNIQUE KEY uq_attachment_workspace_id (workspace_id, id),
    DROP INDEX idx_attachment_entity,
    ADD INDEX idx_attachment_entity (workspace_id, entity_type, entity_id),
    ADD INDEX idx_attachment_workspace (workspace_id);
