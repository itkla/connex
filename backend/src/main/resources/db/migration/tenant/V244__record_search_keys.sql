-- Each column/index verifies an existing committed definition before skipping its DDL on replay.
-- Invalid statement markers deliberately fail PREPARE on incompatible definitions without reading data.

SET @record_search_v244_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN
            'ALTER TABLE person ADD COLUMN search_name VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL, ALGORITHM=INSTANT'
        WHEN COUNT(*) = 1 AND SUM(DATA_TYPE = 'varchar' AND CHARACTER_MAXIMUM_LENGTH = 1024
                 AND CHARACTER_SET_NAME = 'utf8mb4' AND COLLATION_NAME = 'utf8mb4_0900_ai_ci'
                 AND IS_NULLABLE = 'YES' AND COLUMN_DEFAULT IS NULL
                 AND EXTRA = '' AND GENERATION_EXPRESSION = '') = 1 THEN 'DO 0'
        ELSE 'V244_incompatible_person_search_name'
    END
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'person' AND COLUMN_NAME = 'search_name'
);
PREPARE record_search_v244_statement FROM @record_search_v244_ddl;
EXECUTE record_search_v244_statement;
DEALLOCATE PREPARE record_search_v244_statement;

SET @record_search_v244_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN
            'ALTER TABLE person ADD COLUMN search_phone VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL, ALGORITHM=INSTANT'
        WHEN COUNT(*) = 1 AND SUM(DATA_TYPE = 'varchar' AND CHARACTER_MAXIMUM_LENGTH = 256
                 AND CHARACTER_SET_NAME = 'utf8mb4' AND COLLATION_NAME = 'utf8mb4_0900_ai_ci'
                 AND IS_NULLABLE = 'YES' AND COLUMN_DEFAULT IS NULL
                 AND EXTRA = '' AND GENERATION_EXPRESSION = '') = 1 THEN 'DO 0'
        ELSE 'V244_incompatible_person_search_phone'
    END
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'person' AND COLUMN_NAME = 'search_phone'
);
PREPARE record_search_v244_statement FROM @record_search_v244_ddl;
EXECUTE record_search_v244_statement;
DEALLOCATE PREPARE record_search_v244_statement;

SET @record_search_v244_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN
            'ALTER TABLE person ADD COLUMN search_key_revision BIGINT NOT NULL DEFAULT 0, ALGORITHM=INSTANT'
        WHEN COUNT(*) = 1 AND SUM(COLUMN_TYPE = 'bigint' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT = '0'
                 AND CHARACTER_SET_NAME IS NULL AND COLLATION_NAME IS NULL
                 AND EXTRA = '' AND GENERATION_EXPRESSION = '') = 1 THEN 'DO 0'
        ELSE 'V244_incompatible_person_search_key_revision'
    END
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'person' AND COLUMN_NAME = 'search_key_revision'
);
PREPARE record_search_v244_statement FROM @record_search_v244_ddl;
EXECUTE record_search_v244_statement;
DEALLOCATE PREPARE record_search_v244_statement;

SET @record_search_v244_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN
            'ALTER TABLE company ADD COLUMN search_name VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL, ALGORITHM=INSTANT'
        WHEN COUNT(*) = 1 AND SUM(DATA_TYPE = 'varchar' AND CHARACTER_MAXIMUM_LENGTH = 1024
                 AND CHARACTER_SET_NAME = 'utf8mb4' AND COLLATION_NAME = 'utf8mb4_0900_ai_ci'
                 AND IS_NULLABLE = 'YES' AND COLUMN_DEFAULT IS NULL
                 AND EXTRA = '' AND GENERATION_EXPRESSION = '') = 1 THEN 'DO 0'
        ELSE 'V244_incompatible_company_search_name'
    END
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'company' AND COLUMN_NAME = 'search_name'
);
PREPARE record_search_v244_statement FROM @record_search_v244_ddl;
EXECUTE record_search_v244_statement;
DEALLOCATE PREPARE record_search_v244_statement;

SET @record_search_v244_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN
            'ALTER TABLE company ADD COLUMN search_phone VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL, ALGORITHM=INSTANT'
        WHEN COUNT(*) = 1 AND SUM(DATA_TYPE = 'varchar' AND CHARACTER_MAXIMUM_LENGTH = 256
                 AND CHARACTER_SET_NAME = 'utf8mb4' AND COLLATION_NAME = 'utf8mb4_0900_ai_ci'
                 AND IS_NULLABLE = 'YES' AND COLUMN_DEFAULT IS NULL
                 AND EXTRA = '' AND GENERATION_EXPRESSION = '') = 1 THEN 'DO 0'
        ELSE 'V244_incompatible_company_search_phone'
    END
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'company' AND COLUMN_NAME = 'search_phone'
);
PREPARE record_search_v244_statement FROM @record_search_v244_ddl;
EXECUTE record_search_v244_statement;
DEALLOCATE PREPARE record_search_v244_statement;

SET @record_search_v244_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN
            'ALTER TABLE company ADD COLUMN search_key_revision BIGINT NOT NULL DEFAULT 0, ALGORITHM=INSTANT'
        WHEN COUNT(*) = 1 AND SUM(COLUMN_TYPE = 'bigint' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT = '0'
                 AND CHARACTER_SET_NAME IS NULL AND COLLATION_NAME IS NULL
                 AND EXTRA = '' AND GENERATION_EXPRESSION = '') = 1 THEN 'DO 0'
        ELSE 'V244_incompatible_company_search_key_revision'
    END
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'company' AND COLUMN_NAME = 'search_key_revision'
);
PREPARE record_search_v244_statement FROM @record_search_v244_ddl;
EXECUTE record_search_v244_statement;
DEALLOCATE PREPARE record_search_v244_statement;

SET @record_search_v244_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN
            'ALTER TABLE person ADD INDEX idx_person_workspace_search_phone (workspace_id, search_phone, id), ALGORITHM=INPLACE, LOCK=NONE'
        WHEN COUNT(*) = 3 AND SUM(
                 ((SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'workspace_id')
                   OR (SEQ_IN_INDEX = 2 AND COLUMN_NAME = 'search_phone')
                   OR (SEQ_IN_INDEX = 3 AND COLUMN_NAME = 'id'))
                 AND NON_UNIQUE = 1 AND SUB_PART IS NULL AND COLLATION = 'A'
                 AND INDEX_TYPE = 'BTREE' AND IS_VISIBLE = 'YES' AND EXPRESSION IS NULL) = 3 THEN 'DO 0'
        ELSE 'V244_incompatible_person_search_phone_index'
    END
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'person'
      AND INDEX_NAME = 'idx_person_workspace_search_phone'
);
PREPARE record_search_v244_statement FROM @record_search_v244_ddl;
EXECUTE record_search_v244_statement;
DEALLOCATE PREPARE record_search_v244_statement;

SET @record_search_v244_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN
            'ALTER TABLE company ADD INDEX idx_company_workspace_search_phone (workspace_id, search_phone, id), ALGORITHM=INPLACE, LOCK=NONE'
        WHEN COUNT(*) = 3 AND SUM(
                 ((SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'workspace_id')
                   OR (SEQ_IN_INDEX = 2 AND COLUMN_NAME = 'search_phone')
                   OR (SEQ_IN_INDEX = 3 AND COLUMN_NAME = 'id'))
                 AND NON_UNIQUE = 1 AND SUB_PART IS NULL AND COLLATION = 'A'
                 AND INDEX_TYPE = 'BTREE' AND IS_VISIBLE = 'YES' AND EXPRESSION IS NULL) = 3 THEN 'DO 0'
        ELSE 'V244_incompatible_company_search_phone_index'
    END
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'company'
      AND INDEX_NAME = 'idx_company_workspace_search_phone'
);
PREPARE record_search_v244_statement FROM @record_search_v244_ddl;
EXECUTE record_search_v244_statement;
DEALLOCATE PREPARE record_search_v244_statement;


-- The one-character name prefix indexes only the NULL backlog, never name contains lookup.

SET @record_search_v244_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN
            'ALTER TABLE person ADD INDEX idx_person_workspace_search_missing_name (workspace_id, search_name(1), id), ALGORITHM=INPLACE, LOCK=NONE'
        WHEN COUNT(*) = 3 AND SUM(
                 ((SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'workspace_id' AND SUB_PART IS NULL)
                   OR (SEQ_IN_INDEX = 2 AND COLUMN_NAME = 'search_name' AND SUB_PART = 1)
                   OR (SEQ_IN_INDEX = 3 AND COLUMN_NAME = 'id' AND SUB_PART IS NULL))
                 AND NON_UNIQUE = 1 AND COLLATION = 'A'
                 AND INDEX_TYPE = 'BTREE' AND IS_VISIBLE = 'YES' AND EXPRESSION IS NULL) = 3 THEN 'DO 0'
        ELSE 'V244_incompatible_person_search_missing_name_index'
    END
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'person'
      AND INDEX_NAME = 'idx_person_workspace_search_missing_name'
);
PREPARE record_search_v244_statement FROM @record_search_v244_ddl;
EXECUTE record_search_v244_statement;
DEALLOCATE PREPARE record_search_v244_statement;

SET @record_search_v244_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN
            'ALTER TABLE company ADD INDEX idx_company_workspace_search_missing_name (workspace_id, search_name(1), id), ALGORITHM=INPLACE, LOCK=NONE'
        WHEN COUNT(*) = 3 AND SUM(
                 ((SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'workspace_id' AND SUB_PART IS NULL)
                   OR (SEQ_IN_INDEX = 2 AND COLUMN_NAME = 'search_name' AND SUB_PART = 1)
                   OR (SEQ_IN_INDEX = 3 AND COLUMN_NAME = 'id' AND SUB_PART IS NULL))
                 AND NON_UNIQUE = 1 AND COLLATION = 'A'
                 AND INDEX_TYPE = 'BTREE' AND IS_VISIBLE = 'YES' AND EXPRESSION IS NULL) = 3 THEN 'DO 0'
        ELSE 'V244_incompatible_company_search_missing_name_index'
    END
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'company'
      AND INDEX_NAME = 'idx_company_workspace_search_missing_name'
);
PREPARE record_search_v244_statement FROM @record_search_v244_ddl;
EXECUTE record_search_v244_statement;
DEALLOCATE PREPARE record_search_v244_statement;

-- Keep installed invalidation triggers live throughout replay; incompatible definitions fail closed.

SET @record_search_v244_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN 'DO 0'
        WHEN COUNT(*) = 1 AND SUM(EVENT_OBJECT_SCHEMA = DATABASE() AND EVENT_OBJECT_TABLE = 'person'
                 AND EVENT_MANIPULATION = 'UPDATE' AND ACTION_TIMING = 'BEFORE' AND ACTION_ORIENTATION = 'ROW'
                 AND BINARY REGEXP_REPLACE(ACTION_STATEMENT, '[[:space:]]', '') = BINARY
                     'BEGINIFNEW.search_key_revision=OLD.search_key_revisionTHENIFNOT(BINARYNEW.name<=>BINARYOLD.name)THENSETNEW.search_name=NULL;ENDIF;IFNOT(BINARYNEW.phone<=>BINARYOLD.phone)THENSETNEW.search_phone=NULL;ENDIF;ENDIF;END') = 1 THEN 'DO 0'
        ELSE 'V244_incompatible_person_search_keys_trigger'
    END
    FROM information_schema.TRIGGERS
    WHERE TRIGGER_SCHEMA = DATABASE() AND TRIGGER_NAME = 'trg_person_search_keys_legacy_write'
);
PREPARE record_search_v244_statement FROM @record_search_v244_ddl;
EXECUTE record_search_v244_statement;
DEALLOCATE PREPARE record_search_v244_statement;

DELIMITER //
CREATE TRIGGER IF NOT EXISTS trg_person_search_keys_legacy_write
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
DELIMITER ;

SET @record_search_v244_ddl = (
    SELECT CASE
        WHEN COUNT(*) = 0 THEN 'DO 0'
        WHEN COUNT(*) = 1 AND SUM(EVENT_OBJECT_SCHEMA = DATABASE() AND EVENT_OBJECT_TABLE = 'company'
                 AND EVENT_MANIPULATION = 'UPDATE' AND ACTION_TIMING = 'BEFORE' AND ACTION_ORIENTATION = 'ROW'
                 AND BINARY REGEXP_REPLACE(ACTION_STATEMENT, '[[:space:]]', '') = BINARY
                     'BEGINIFNEW.search_key_revision=OLD.search_key_revisionTHENIFNOT(BINARYNEW.name<=>BINARYOLD.name)THENSETNEW.search_name=NULL;ENDIF;IFNOT(BINARYNEW.phone<=>BINARYOLD.phone)THENSETNEW.search_phone=NULL;ENDIF;ENDIF;END') = 1 THEN 'DO 0'
        ELSE 'V244_incompatible_company_search_keys_trigger'
    END
    FROM information_schema.TRIGGERS
    WHERE TRIGGER_SCHEMA = DATABASE() AND TRIGGER_NAME = 'trg_company_search_keys_legacy_write'
);
PREPARE record_search_v244_statement FROM @record_search_v244_ddl;
EXECUTE record_search_v244_statement;
DEALLOCATE PREPARE record_search_v244_statement;

DELIMITER //
CREATE TRIGGER IF NOT EXISTS trg_company_search_keys_legacy_write
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
