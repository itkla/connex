ALTER TABLE person_identity
    ADD COLUMN superseded_at DATETIME NULL
        COMMENT 'When this identifier stopped representing the current person field value',
    ADD INDEX idx_person_identity_current_lookup
        (kind, normalized_value, superseded_at, workspace_id, person_id);

ALTER TABLE company_identity
    ADD COLUMN superseded_at DATETIME NULL
        COMMENT 'When this identifier stopped representing the current company field value',
    ADD INDEX idx_company_identity_current_lookup
        (kind, normalized_value, superseded_at, workspace_id, company_id);

ALTER TABLE person
    ADD COLUMN normalized_name VARCHAR(255)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL
        COMMENT 'Canonical exact-match name produced by MatchingService',
    ADD INDEX idx_person_normalized_name
        (normalized_name, workspace_id, id);

ALTER TABLE company
    ADD COLUMN normalized_name VARCHAR(255)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL
        COMMENT 'Canonical exact-match name produced by MatchingService',
    ADD INDEX idx_company_normalized_name
        (normalized_name, workspace_id, id);

UPDATE person
SET normalized_name = LOWER(TRIM(name))
WHERE normalized_name IS NULL;

UPDATE company
SET normalized_name = LOWER(TRIM(name))
WHERE normalized_name IS NULL;
