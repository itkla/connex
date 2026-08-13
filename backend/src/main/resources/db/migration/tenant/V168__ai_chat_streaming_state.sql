ALTER TABLE ai_chat_turn
    DROP CHECK chk_ai_chat_turn_status,
    ADD COLUMN privacy_mode VARCHAR(16)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'masked'
        AFTER terminal_reason,
    ADD COLUMN streamed BOOLEAN NOT NULL DEFAULT FALSE
        AFTER privacy_mode,
    ADD COLUMN partial_content MEDIUMTEXT
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL
        AFTER streamed,
    ADD COLUMN partial_content_utf16_offset INT UNSIGNED NOT NULL DEFAULT 0
        AFTER partial_content,
    ADD COLUMN cancel_requested_at DATETIME(6) NULL
        AFTER partial_content_utf16_offset,
    ADD CONSTRAINT chk_ai_chat_turn_status
        CHECK (status IN ('queued', 'running', 'resolved', 'failed', 'timed_out', 'cancelled')),
    ADD CONSTRAINT chk_ai_chat_turn_privacy_mode
        CHECK (privacy_mode IN ('masked', 'unmasked')),
    ADD CONSTRAINT chk_ai_chat_turn_partial_offset
        CHECK (partial_content_utf16_offset <= 16000),
    ADD CONSTRAINT chk_ai_chat_turn_partial_state
        CHECK ((partial_content IS NULL AND partial_content_utf16_offset = 0)
            OR (streamed = TRUE AND partial_content IS NOT NULL));
