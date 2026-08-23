-- A turn records which declared skill produced it and the exact query scope it was authorized to
-- read. Both are evaluation and audit metadata: the skill key/version make an answer reproducible
-- against a specific catalog declaration, and the interpreted scope is what the requester was shown
-- before the turn ran, so a later read cannot restate the breadth differently.
ALTER TABLE ai_chat_turn
    ADD COLUMN skill_key VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER terminal_reason,
    ADD COLUMN skill_version VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER skill_key,
    ADD COLUMN scope_json JSON NULL
        AFTER skill_version,
    ADD CONSTRAINT chk_ai_chat_turn_skill_pairing
        CHECK ((skill_key IS NULL AND skill_version IS NULL)
            OR (skill_key IS NOT NULL AND skill_version IS NOT NULL));

CREATE INDEX idx_ai_chat_turn_skill
    ON ai_chat_turn (workspace_id, skill_key, created_at, id);
