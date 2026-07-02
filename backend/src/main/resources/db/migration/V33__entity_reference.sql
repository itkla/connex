-- ============================================================================
-- Generalizes note_reference into a polymorphic entity_reference: the structured
-- projection of inline @/# tokens ([Label](type:id)) from ANY entity's prose
-- field, not just notes. (source_type, source_id) identifies the entity the
-- reference appears in (note, task, …); (ref_type, ref_id) the referenced entity.
-- Like the attachment table, ref_id is polymorphic across ref_type and carries no
-- per-target FK; unlike note_reference there is no per-source FK either (the
-- source is polymorphic too), so tenant isolation rests on workspace_id being on
-- every row and every query, and deleting a source purges its references at the
-- service layer (no cascade to lean on). Existing note references migrate across.
-- ============================================================================

CREATE TABLE entity_reference (
    workspace_id INT          NOT NULL COMMENT 'Owning workspace (tenant boundary)',
    source_type  VARCHAR(32)  NOT NULL COMMENT 'Entity type the reference appears in (note, task)',
    source_id    INT          NOT NULL COMMENT 'Entity ID the reference appears in',
    ref_type     VARCHAR(32)  NOT NULL COMMENT 'Referenced entity type (user, person, deal, company)',
    ref_id       INT          NOT NULL COMMENT 'Referenced entity ID (polymorphic across ref_type)',
    label        VARCHAR(255) NOT NULL COMMENT 'Frozen display label as authored',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Reference creation timestamp',
    PRIMARY KEY (workspace_id, source_type, source_id, ref_type, ref_id),
    INDEX idx_entity_reference_target (workspace_id, ref_type, ref_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Inline @/# references from an entity prose field (notes, tasks, …)';

INSERT INTO entity_reference (workspace_id, source_type, source_id, ref_type, ref_id, label, created_at)
SELECT workspace_id, 'note', note_id, ref_type, ref_id, label, created_at
FROM note_reference;

DROP TABLE note_reference;
