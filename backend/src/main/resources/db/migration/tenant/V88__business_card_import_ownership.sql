DELETE FROM business_card_import_request
WHERE created_by_user_id IS NULL;

ALTER TABLE business_card_import_request
    MODIFY COLUMN created_by_user_id INT NOT NULL AFTER idempotency_key;
