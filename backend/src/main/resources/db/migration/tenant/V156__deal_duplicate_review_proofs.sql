ALTER TABLE deal
    ADD COLUMN duplicate_normalized_name VARCHAR(255)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER name,
    ADD COLUMN duplicate_name_revision BIGINT UNSIGNED NOT NULL DEFAULT 0
        AFTER duplicate_normalized_name,
    ADD INDEX idx_deal_workspace_company_duplicate_name
        (workspace_id, company_id, duplicate_normalized_name, id);

CREATE TABLE deal_duplicate_review_proof (
    token_hash BINARY(32) NOT NULL,
    workspace_id INT NOT NULL,
    actor_id INT NOT NULL,
    workflow_hash BINARY(32) NOT NULL,
    result_hash BINARY(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (token_hash),
    KEY idx_deal_duplicate_review_proof_expiry (expires_at, workspace_id),
    KEY idx_deal_duplicate_review_proof_workspace_expiry (workspace_id, expires_at),
    KEY idx_deal_duplicate_review_proof_actor (actor_id, token_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DELIMITER //
CREATE TRIGGER trg_deal_duplicate_name_legacy_write
BEFORE UPDATE ON deal
FOR EACH ROW
BEGIN
    IF NOT (BINARY OLD.name <=> BINARY NEW.name)
            AND NEW.duplicate_name_revision = OLD.duplicate_name_revision THEN
        SET NEW.duplicate_normalized_name = NULL;
    END IF;
END//
DELIMITER ;
