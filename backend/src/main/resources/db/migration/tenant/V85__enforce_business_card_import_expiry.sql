ALTER TABLE business_card_import_request
    MODIFY COLUMN expires_at DATETIME(6) NOT NULL,
    ADD INDEX idx_business_card_import_request_workspace_expiry
        (workspace_id, expires_at, idempotency_key),
    ADD INDEX idx_business_card_import_request_expiry
        (expires_at, workspace_id, idempotency_key);
