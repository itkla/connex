-- NULL means the referenced account was permanently erased; the transcript is retained as workspace data.
ALTER TABLE ai_chat_session
    MODIFY created_by_user_id INT NULL;

ALTER TABLE ai_chat_turn
    MODIFY requested_by_user_id INT NULL;
