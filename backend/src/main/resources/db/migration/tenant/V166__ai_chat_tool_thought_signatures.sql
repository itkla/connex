ALTER TABLE ai_chat_tool_call
    ADD COLUMN thought_signature MEDIUMTEXT
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL
        AFTER arguments_json;
