ALTER TABLE user_object_deletion_queue
    ADD COLUMN delete_passes_remaining TINYINT UNSIGNED NOT NULL DEFAULT 1 AFTER attempts,
    ADD CONSTRAINT chk_user_object_deletion_queue_passes
        CHECK (delete_passes_remaining BETWEEN 1 AND 2);
