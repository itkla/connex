CREATE TABLE business_card_import_request (
    workspace_id INT NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_fingerprint BINARY(32) NOT NULL,
    person_id INT NULL,
    attachment_id INT NULL,
    company_id INT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    PRIMARY KEY (workspace_id, idempotency_key),
    KEY idx_business_card_import_request_created_at (created_at)
) ENGINE=InnoDB;
