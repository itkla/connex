ALTER TABLE business_card_import_request
    ADD COLUMN created_by_user_id INT NULL AFTER idempotency_key,
    ADD COLUMN submission_expires_at DATETIME(6) NULL AFTER expires_at,
    ADD COLUMN reservation_slot TINYINT UNSIGNED NULL AFTER submission_expires_at;

DELETE FROM business_card_import_request
WHERE request_fingerprint IS NULL;

ALTER TABLE business_card_import_request
    ADD CONSTRAINT chk_business_card_import_reservation_slot
        CHECK (reservation_slot BETWEEN 1 AND 32),
    ADD CONSTRAINT chk_business_card_import_reservation_state
        CHECK (
            (request_fingerprint IS NULL
                AND created_by_user_id IS NOT NULL
                AND submission_expires_at IS NOT NULL
                AND reservation_slot IS NOT NULL)
            OR
            (request_fingerprint IS NOT NULL
                AND submission_expires_at IS NULL
                AND reservation_slot IS NULL)
        ),
    ADD UNIQUE INDEX uq_business_card_import_user_reservation_slot
        (workspace_id, created_by_user_id, reservation_slot),
    ADD INDEX idx_business_card_import_user_abandoned
        (workspace_id, created_by_user_id, request_fingerprint, submission_expires_at);
