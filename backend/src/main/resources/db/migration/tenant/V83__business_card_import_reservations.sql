ALTER TABLE business_card_import_request
    MODIFY COLUMN request_fingerprint BINARY(32) NULL,
    ADD COLUMN expires_at DATETIME(6) NULL AFTER completed_at;
