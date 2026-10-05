-- ============================================================================
-- Marks the tool-call rows whose proposal envelope the server wrote (#1867).
--
-- A refused call stores the model's own arguments, and a model can shape those
-- arguments like a proposal envelope. The transcript card projection renders a
-- failed row only when this flag is set, so a refused call can never be shown as
-- a proposal the server validated. Rows written before this column exist as
-- false: refused rows and genuine proposals alike, so a failed one of either
-- kind no longer renders as a card.
-- ============================================================================

ALTER TABLE ai_chat_tool_call
    ADD COLUMN server_proposal BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'The server wrote this row''s proposal envelope',
    ALGORITHM=INSTANT;
