-- ============================================================================
-- Inline @-references within notes. A note's content carries tokens of the form
-- [Label](type:id); this table is the structured projection of those tokens. It
-- drives member-mention notifications (ref_type='user') and, later, record-
-- reference chips/hover cards (person/deal/company — see issue #190). ref_id is
-- polymorphic across ref_type (mirrors the attachment table); only the
-- (workspace_id, note_id) pair is FK-enforced, which anchors tenant isolation.
-- The composite FK needs note(workspace_id, id) as a key — V7 deferred it.
-- ============================================================================

ALTER TABLE note ADD UNIQUE KEY uq_note_workspace_id (workspace_id, id);

CREATE TABLE note_reference (
    workspace_id INT          NOT NULL COMMENT 'Owning workspace (tenant boundary)',
    note_id      INT          NOT NULL COMMENT 'Note the reference appears in',
    ref_type     VARCHAR(32)  NOT NULL COMMENT 'Referenced entity type (user, person, deal, company)',
    ref_id       INT          NOT NULL COMMENT 'Referenced entity ID (polymorphic across ref_type)',
    label        VARCHAR(255) NOT NULL COMMENT 'Frozen display label as authored',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Reference creation timestamp',
    PRIMARY KEY (workspace_id, note_id, ref_type, ref_id),
    CONSTRAINT fk_note_reference_note FOREIGN KEY (workspace_id, note_id)
        REFERENCES note(workspace_id, id) ON DELETE CASCADE,
    INDEX idx_note_reference_target (workspace_id, ref_type, ref_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Inline @-references (members, contacts, deals, companies) within notes';
