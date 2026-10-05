-- ============================================================================
-- Marks the tool-call rows whose proposal envelope the server wrote (#1867).
--
-- A refused call stores the model's own arguments, and a model can shape those
-- arguments like a proposal envelope; an interrupted run can even leave such a
-- row pending. A pending or failed row renders as a card, and a pending one can
-- be approved or rejected, only when this flag is set, so a refused call can
-- never pass as a proposal the server validated. Rows written before this
-- column exist as false: refused rows and genuine proposals alike, so a legacy
-- pending or failed proposal of either kind no longer renders and cannot be
-- decided. Executed and rejected rows stay as history, and executed ones can
-- still be undone.
-- ============================================================================

ALTER TABLE ai_chat_tool_call
    ADD COLUMN server_proposal BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'The server wrote this row''s proposal envelope',
    ALGORITHM=INSTANT;
