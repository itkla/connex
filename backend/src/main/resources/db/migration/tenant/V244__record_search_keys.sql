-- Add derived projections separately from indexes; refuse a table-copy fallback.
ALTER TABLE person
    ADD COLUMN search_name VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
    ADD COLUMN search_phone VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
    ADD COLUMN search_key_revision BIGINT NOT NULL DEFAULT 0,
    ALGORITHM=INSTANT;

ALTER TABLE company
    ADD COLUMN search_name VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
    ADD COLUMN search_phone VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
    ADD COLUMN search_key_revision BIGINT NOT NULL DEFAULT 0,
    ALGORITHM=INSTANT;

ALTER TABLE person
    ADD INDEX idx_person_workspace_search_name (workspace_id, search_name(191), id),
    ADD INDEX idx_person_workspace_search_phone (workspace_id, search_phone, id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE company
    ADD INDEX idx_company_workspace_search_name (workspace_id, search_name(191), id),
    ADD INDEX idx_company_workspace_search_phone (workspace_id, search_phone, id),
    ALGORITHM=INPLACE, LOCK=NONE;

-- Old binaries and privacy scrubbers cannot leave an obsolete searchable projection behind.
DELIMITER //
CREATE TRIGGER trg_person_search_keys_legacy_write
BEFORE UPDATE ON person
FOR EACH ROW
BEGIN
    IF NEW.search_key_revision = OLD.search_key_revision THEN
        IF NOT (BINARY NEW.name <=> BINARY OLD.name) THEN
            SET NEW.search_name = NULL;
        END IF;
        IF NOT (BINARY NEW.phone <=> BINARY OLD.phone) THEN
            SET NEW.search_phone = NULL;
        END IF;
    END IF;
END//
CREATE TRIGGER trg_company_search_keys_legacy_write
BEFORE UPDATE ON company
FOR EACH ROW
BEGIN
    IF NEW.search_key_revision = OLD.search_key_revision THEN
        IF NOT (BINARY NEW.name <=> BINARY OLD.name) THEN
            SET NEW.search_name = NULL;
        END IF;
        IF NOT (BINARY NEW.phone <=> BINARY OLD.phone) THEN
            SET NEW.search_phone = NULL;
        END IF;
    END IF;
END//
DELIMITER ;
