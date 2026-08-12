ALTER TABLE ai_chat_session
    ADD COLUMN title_user_set BOOLEAN NOT NULL DEFAULT TRUE AFTER title;
