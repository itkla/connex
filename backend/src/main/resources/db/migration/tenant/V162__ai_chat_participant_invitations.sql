ALTER TABLE ai_chat_session_participant
    ADD COLUMN status VARCHAR(16)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'joined'
        AFTER role,
    ADD COLUMN invited_by_user_id INT NULL AFTER user_id,
    ADD COLUMN invited_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        AFTER status,
    MODIFY joined_at DATETIME(6) NULL DEFAULT NULL,
    ADD CONSTRAINT chk_ai_chat_session_participant_status
        CHECK (status IN ('invited', 'joined')),
    ADD INDEX idx_ai_chat_session_participant_invitation
        (workspace_id, user_id, status, session_id),
    ADD INDEX idx_ai_chat_session_participant_invited_by
        (invited_by_user_id, workspace_id);
