UPDATE business_card_import_request
SET expires_at = DATE_ADD(created_at, INTERVAL 24 HOUR)
WHERE expires_at IS NULL;
