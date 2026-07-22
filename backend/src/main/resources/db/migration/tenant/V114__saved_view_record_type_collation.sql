ALTER TABLE saved_view_default
    DROP FOREIGN KEY fk_saved_view_default_view;

ALTER TABLE saved_view
    MODIFY COLUMN record_type VARCHAR(16)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL;

ALTER TABLE saved_view_default
    MODIFY COLUMN record_type VARCHAR(16)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL;

ALTER TABLE saved_view_default
    ADD CONSTRAINT fk_saved_view_default_view
        FOREIGN KEY (workspace_id, saved_view_id, record_type)
        REFERENCES saved_view(workspace_id, id, record_type) ON DELETE CASCADE;
