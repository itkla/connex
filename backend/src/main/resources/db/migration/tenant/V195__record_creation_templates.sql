-- ============================================================================
-- Workspace-scoped record creation templates (#907).
--
-- System presets are code-owned and are not persisted. These tables hold only
-- workspace-authored configuration and immutable definition versions, so they
-- belong to the tenant plane and participate in export and teardown.
--
-- Actor identifiers deliberately have no app_user foreign keys. app_user is
-- control-plane state and cross-plane foreign keys are forbidden.
-- ============================================================================

CREATE TABLE record_creation_template_set (
    workspace_id        INT NOT NULL,
    record_type         VARCHAR(16)
                            CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision            INT UNSIGNED NOT NULL DEFAULT 0,
    default_template_id INT NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                            ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_record_creation_template_set_type
        CHECK (record_type IN ('person', 'company', 'deal')),

    PRIMARY KEY (workspace_id, record_type),
    INDEX idx_record_creation_template_set_default
        (workspace_id, default_template_id)
) DEFAULT CHARSET=utf8mb4;

CREATE TABLE record_creation_template (
    id                 INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id       INT NOT NULL,
    record_type        VARCHAR(16)
                           CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status             VARCHAR(16)
                           CHARACTER SET ascii COLLATE ascii_bin
                           NOT NULL DEFAULT 'disabled',
    position           INT UNSIGNED NOT NULL DEFAULT 0,
    revision           INT UNSIGNED NOT NULL DEFAULT 0,
    current_version_id BIGINT NULL,
    created_by_id      INT NULL,
    updated_by_id      INT NULL,
    archived_at        DATETIME(6) NULL,
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                           ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_record_creation_template_type
        CHECK (record_type IN ('person', 'company', 'deal')),
    CONSTRAINT chk_record_creation_template_status
        CHECK (status IN ('enabled', 'disabled', 'archived')),
    CONSTRAINT chk_record_creation_template_archive
        CHECK (
            (status = 'archived' AND archived_at IS NOT NULL)
            OR
            (status IN ('enabled', 'disabled') AND archived_at IS NULL)
        ),
    CONSTRAINT fk_record_creation_template_set
        FOREIGN KEY (workspace_id, record_type)
        REFERENCES record_creation_template_set(workspace_id, record_type)
        ON DELETE CASCADE,

    UNIQUE KEY uq_record_creation_template_workspace_id
        (workspace_id, id),
    INDEX idx_record_creation_template_order
        (workspace_id, record_type, status, position, id),
    INDEX idx_record_creation_template_current
        (workspace_id, id, current_version_id),
    INDEX idx_record_creation_template_created_by
        (created_by_id, workspace_id),
    INDEX idx_record_creation_template_updated_by
        (updated_by_id, workspace_id)
) DEFAULT CHARSET=utf8mb4;

CREATE TABLE record_creation_template_version (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id        INT NOT NULL,
    template_id         INT NOT NULL,
    version_number      INT UNSIGNED NOT NULL,
    name_en             VARCHAR(128) NOT NULL,
    name_ja             VARCHAR(128) NOT NULL,
    description_en      VARCHAR(512) NULL,
    description_ja      VARCHAR(512) NULL,
    definition_json     MEDIUMTEXT NOT NULL,
    definition_hash     BINARY(32) NOT NULL,
    created_by_id       INT NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_record_creation_template_version_number
        CHECK (version_number > 0),
    CONSTRAINT chk_record_creation_template_version_name_en
        CHECK (CHAR_LENGTH(TRIM(name_en)) BETWEEN 1 AND 128),
    CONSTRAINT chk_record_creation_template_version_name_ja
        CHECK (CHAR_LENGTH(TRIM(name_ja)) BETWEEN 1 AND 128),
    CONSTRAINT chk_record_creation_template_version_description_en
        CHECK (
            description_en IS NULL
            OR CHAR_LENGTH(TRIM(description_en)) BETWEEN 1 AND 512
        ),
    CONSTRAINT chk_record_creation_template_version_description_ja
        CHECK (
            description_ja IS NULL
            OR CHAR_LENGTH(TRIM(description_ja)) BETWEEN 1 AND 512
        ),
    CONSTRAINT chk_record_creation_template_version_definition
        CHECK (
            JSON_VALID(definition_json) = 1
            AND JSON_TYPE(definition_json) = 'OBJECT'
            AND JSON_CONTAINS_PATH(
                definition_json,
                'one',
                '$.schemaVersion',
                '$.groups'
            ) = 1
            AND JSON_TYPE(
                JSON_EXTRACT(definition_json, '$.schemaVersion')
            ) = 'INTEGER'
            AND JSON_UNQUOTE(
                JSON_EXTRACT(definition_json, '$.schemaVersion')
            ) = '1'
            AND JSON_TYPE(
                JSON_EXTRACT(definition_json, '$.groups')
            ) = 'ARRAY'
            AND OCTET_LENGTH(definition_json) <= 131072
        ),
    CONSTRAINT fk_record_creation_template_version_template
        FOREIGN KEY (workspace_id, template_id)
        REFERENCES record_creation_template(workspace_id, id)
        ON DELETE CASCADE,

    UNIQUE KEY uq_record_creation_template_version_identity
        (workspace_id, template_id, id),
    UNIQUE KEY uq_record_creation_template_version_number
        (workspace_id, template_id, version_number),
    INDEX idx_record_creation_template_version_created_by
        (created_by_id, workspace_id)
) DEFAULT CHARSET=utf8mb4;

ALTER TABLE record_creation_template
    ADD CONSTRAINT fk_record_creation_template_current_version
        FOREIGN KEY (workspace_id, id, current_version_id)
        REFERENCES record_creation_template_version(
            workspace_id,
            template_id,
            id
        )
        ON DELETE RESTRICT;
