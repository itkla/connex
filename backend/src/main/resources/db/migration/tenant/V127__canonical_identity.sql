CREATE TABLE person_identity (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Person identity row ID',
    workspace_id        INT NOT NULL COMMENT 'Owning workspace ID',
    person_id           INT NOT NULL COMMENT 'Person carrying this identity',
    kind                VARCHAR(16)
                            CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                            NOT NULL COMMENT 'Identity kind: email, phone, or external_id',
    `value`             VARCHAR(2048) NOT NULL COMMENT 'Raw identifier value as acquired',
    normalized_value    VARCHAR(512)
                            CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                            NOT NULL COMMENT 'Canonical matching value produced by MatchingService',
    source_system       VARCHAR(32) NOT NULL COMMENT 'Acquisition source system code',
    source_channel      VARCHAR(64) NULL COMMENT 'Acquisition channel or source field locator',
    source_external_id  VARCHAR(512) NULL COMMENT 'Source-system record identifier',
    source_row_ref      VARCHAR(512) NULL COMMENT 'Source row or import-record reference',
    acquired_at         DATETIME NOT NULL COMMENT 'When the identifier was originally acquired',
    purpose_of_use_code VARCHAR(64)
                            CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                            NULL COMMENT 'Purpose-of-use code pending the governed purpose registry',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Identity row creation timestamp',
    CONSTRAINT chk_person_identity_kind
        CHECK (kind IN ('email', 'phone', 'external_id')),
    CONSTRAINT chk_person_identity_value
        CHECK (CHAR_LENGTH(TRIM(`value`)) > 0),
    CONSTRAINT chk_person_identity_normalized_value
        CHECK (CHAR_LENGTH(normalized_value) > 0),
    CONSTRAINT chk_person_identity_source_system
        CHECK (CHAR_LENGTH(TRIM(source_system)) > 0),
    UNIQUE KEY uq_person_identity_workspace_id (workspace_id, id),
    UNIQUE KEY uq_person_identity_workspace_kind_normalized_value_person_id
        (workspace_id, kind, normalized_value, person_id),
    INDEX idx_person_identity_workspace_person (workspace_id, person_id, id),
    CONSTRAINT fk_person_identity_person
        FOREIGN KEY (workspace_id, person_id)
        REFERENCES person(workspace_id, id) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Multi-valued canonical identifiers and acquisition provenance for persons';

CREATE TABLE company_identity (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Company identity row ID',
    workspace_id        INT NOT NULL COMMENT 'Owning workspace ID',
    company_id          INT NOT NULL COMMENT 'Company carrying this identity',
    kind                VARCHAR(16)
                            CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                            NOT NULL COMMENT 'Identity kind: domain, phone, or external_id',
    `value`             VARCHAR(2048) NOT NULL COMMENT 'Raw identifier value as acquired',
    normalized_value    VARCHAR(512)
                            CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                            NOT NULL COMMENT 'Canonical matching value produced by MatchingService',
    source_system       VARCHAR(32) NOT NULL COMMENT 'Acquisition source system code',
    source_channel      VARCHAR(64) NULL COMMENT 'Acquisition channel or source field locator',
    source_external_id  VARCHAR(512) NULL COMMENT 'Source-system record identifier',
    source_row_ref      VARCHAR(512) NULL COMMENT 'Source row or import-record reference',
    acquired_at         DATETIME NOT NULL COMMENT 'When the identifier was originally acquired',
    purpose_of_use_code VARCHAR(64)
                            CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                            NULL COMMENT 'Purpose-of-use code pending the governed purpose registry',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Identity row creation timestamp',
    CONSTRAINT chk_company_identity_kind
        CHECK (kind IN ('domain', 'phone', 'external_id')),
    CONSTRAINT chk_company_identity_value
        CHECK (CHAR_LENGTH(TRIM(`value`)) > 0),
    CONSTRAINT chk_company_identity_normalized_value
        CHECK (CHAR_LENGTH(normalized_value) > 0),
    CONSTRAINT chk_company_identity_source_system
        CHECK (CHAR_LENGTH(TRIM(source_system)) > 0),
    UNIQUE KEY uq_company_identity_workspace_id (workspace_id, id),
    UNIQUE KEY uq_company_identity_workspace_kind_normalized_value_company_id
        (workspace_id, kind, normalized_value, company_id),
    INDEX idx_company_identity_workspace_company (workspace_id, company_id, id),
    CONSTRAINT fk_company_identity_company
        FOREIGN KEY (workspace_id, company_id)
        REFERENCES company(workspace_id, id) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Multi-valued canonical identifiers and acquisition provenance for companies';
