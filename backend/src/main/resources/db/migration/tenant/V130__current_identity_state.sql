ALTER TABLE person_identity
    ADD COLUMN superseded_at DATETIME NULL
        COMMENT 'When this identifier stopped representing the current person field value',
    ADD INDEX idx_person_identity_current_lookup
        (workspace_id, kind, normalized_value, superseded_at, person_id);

ALTER TABLE company_identity
    ADD COLUMN superseded_at DATETIME NULL
        COMMENT 'When this identifier stopped representing the current company field value',
    ADD INDEX idx_company_identity_current_lookup
        (workspace_id, kind, normalized_value, superseded_at, company_id);

UPDATE person_identity
SET acquired_at = CURRENT_TIMESTAMP,
    superseded_at = CURRENT_TIMESTAMP
WHERE source_system = 'backfill';

UPDATE company_identity
SET acquired_at = CURRENT_TIMESTAMP,
    superseded_at = CURRENT_TIMESTAMP
WHERE source_system = 'backfill';
