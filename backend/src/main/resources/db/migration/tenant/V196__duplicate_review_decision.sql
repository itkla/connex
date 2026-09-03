CREATE TABLE duplicate_review_decision (
    id                           BIGINT AUTO_INCREMENT PRIMARY KEY
        COMMENT 'Duplicate review item ID',
    workspace_id                 INT NOT NULL COMMENT 'Owning workspace ID',
    record_type                  VARCHAR(16)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
        COMMENT 'Reviewed record type: person or company',
    kind                         VARCHAR(16)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL
        COMMENT 'Canonical identity kind',
    evidence_fingerprint         CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'SHA-256 fingerprint of canonical evidence',
    low_person_id                INT NULL COMMENT 'Lower person ID for a person pair',
    high_person_id               INT NULL COMMENT 'Higher person ID for a person pair',
    low_company_id               INT NULL COMMENT 'Lower company ID for a company pair',
    high_company_id              INT NULL COMMENT 'Higher company ID for a company pair',
    evidence_person_identity_id  BIGINT NULL COMMENT 'Person identity carrying the evidence',
    evidence_company_identity_id BIGINT NULL COMMENT 'Company identity carrying the evidence',
    oversized_marker             TINYINT NULL COMMENT 'One for an oversized group row',
    collision_size               INT NOT NULL COMMENT 'Group size at the latest refresh',
    state                        VARCHAR(16)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'open'
        COMMENT 'Review state: open or dismissed',
    is_current                   BOOLEAN NOT NULL DEFAULT TRUE
        COMMENT 'Whether the exact evidence is currently colliding',
    detected_at                  DATETIME(6) NOT NULL COMMENT 'First detection timestamp',
    dismissed_at                 DATETIME(6) NULL COMMENT 'Latest dismissal timestamp',
    dismissed_by_user_id         INT NULL COMMENT 'Latest dismissing actor ID',
    dismissal_note               VARCHAR(500) NULL COMMENT 'Optional bounded reviewer note',
    created_at                   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_duplicate_review_decision_shape
        CHECK (
            (
                record_type = 'person'
                AND evidence_person_identity_id IS NOT NULL
                AND evidence_company_identity_id IS NULL
                AND (
                    (
                        low_person_id IS NOT NULL
                        AND high_person_id IS NOT NULL
                        AND low_person_id < high_person_id
                        AND low_company_id IS NULL
                        AND high_company_id IS NULL
                        AND oversized_marker IS NULL
                        AND collision_size BETWEEN 2 AND 20
                    )
                    OR
                    (
                        low_person_id IS NULL
                        AND high_person_id IS NULL
                        AND low_company_id IS NULL
                        AND high_company_id IS NULL
                        AND oversized_marker = 1
                        AND collision_size > 20
                    )
                )
            )
            OR
            (
                record_type = 'company'
                AND evidence_person_identity_id IS NULL
                AND evidence_company_identity_id IS NOT NULL
                AND (
                    (
                        low_person_id IS NULL
                        AND high_person_id IS NULL
                        AND low_company_id IS NOT NULL
                        AND high_company_id IS NOT NULL
                        AND low_company_id < high_company_id
                        AND oversized_marker IS NULL
                        AND collision_size BETWEEN 2 AND 20
                    )
                    OR
                    (
                        low_person_id IS NULL
                        AND high_person_id IS NULL
                        AND low_company_id IS NULL
                        AND high_company_id IS NULL
                        AND oversized_marker = 1
                        AND collision_size > 20
                    )
                )
            )
        ),
    CONSTRAINT chk_duplicate_review_decision_state
        CHECK (state IN ('open', 'dismissed')),
    CONSTRAINT chk_duplicate_review_decision_kind
        CHECK (
            (record_type = 'person' AND kind IN ('email', 'phone', 'external_id'))
            OR
            (record_type = 'company' AND kind IN ('domain', 'phone', 'external_id'))
        ),
    UNIQUE KEY uq_duplicate_review_workspace_id (workspace_id, id),
    UNIQUE KEY uq_duplicate_review_person_pair_evidence
        (workspace_id, kind, evidence_fingerprint, low_person_id, high_person_id),
    UNIQUE KEY uq_duplicate_review_company_pair_evidence
        (workspace_id, kind, evidence_fingerprint, low_company_id, high_company_id),
    UNIQUE KEY uq_duplicate_review_oversized_evidence
        (workspace_id, record_type, kind, evidence_fingerprint, oversized_marker),
    INDEX idx_duplicate_review_queue
        (workspace_id, is_current, state, detected_at DESC, id DESC),
    INDEX idx_duplicate_review_summary
        (workspace_id, is_current, state, record_type),
    INDEX idx_duplicate_review_dismissing_actor
        (dismissed_by_user_id, workspace_id),
    CONSTRAINT fk_duplicate_review_low_person
        FOREIGN KEY (workspace_id, low_person_id)
        REFERENCES person(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_duplicate_review_high_person
        FOREIGN KEY (workspace_id, high_person_id)
        REFERENCES person(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_duplicate_review_low_company
        FOREIGN KEY (workspace_id, low_company_id)
        REFERENCES company(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_duplicate_review_high_company
        FOREIGN KEY (workspace_id, high_company_id)
        REFERENCES company(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_duplicate_review_person_evidence
        FOREIGN KEY (workspace_id, evidence_person_identity_id)
        REFERENCES person_identity(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_duplicate_review_company_evidence
        FOREIGN KEY (workspace_id, evidence_company_identity_id)
        REFERENCES company_identity(workspace_id, id) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Evidence-specific duplicate review state and suppression memory';
