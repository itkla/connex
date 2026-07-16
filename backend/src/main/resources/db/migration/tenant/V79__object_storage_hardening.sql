ALTER TABLE object_deletion_queue
    ADD COLUMN delete_passes_remaining TINYINT UNSIGNED NOT NULL DEFAULT 1 AFTER attempts,
    ADD CONSTRAINT chk_object_deletion_queue_passes
        CHECK (delete_passes_remaining BETWEEN 1 AND 2),
    DROP INDEX idx_object_deletion_queue_due,
    ADD INDEX idx_object_deletion_queue_workspace_due (workspace_id, next_attempt_at, id),
    ADD INDEX idx_object_deletion_queue_due (next_attempt_at, workspace_id, id);

ALTER TABLE business_card_import_request
    ADD INDEX idx_business_card_import_request_workspace_created (workspace_id, created_at);

ALTER TABLE attachment
    ADD INDEX idx_attachment_workspace_url (workspace_id, url(191));
