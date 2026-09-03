-- Workspace-scoped sales sequence templates and immutable step definitions (#561).
-- Sending policy fields are inert until the separately gated runtime increment.
-- User identifiers have no control-plane foreign keys.

CREATE TABLE sequence (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id      INT NOT NULL,
    name              VARCHAR(128) NOT NULL,
    purpose           VARCHAR(512) NULL,
    owner_id          INT NULL,
    visibility        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status            VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'draft',
    timezone          VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    weekday_mask      TINYINT UNSIGNED NOT NULL,
    send_window_start TIME NOT NULL,
    send_window_end   TIME NOT NULL,
    created_by_id     INT NULL,
    updated_by_id     INT NULL,
    archived_at       DATETIME(6) NULL,
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                          ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_sequence_name
        CHECK (CHAR_LENGTH(TRIM(name)) BETWEEN 1 AND 128),
    CONSTRAINT chk_sequence_visibility
        CHECK (visibility IN ('personal', 'shared')),
    CONSTRAINT chk_sequence_status
        CHECK (status IN ('draft', 'active', 'archived')),
    CONSTRAINT chk_sequence_weekday_mask
        CHECK (weekday_mask BETWEEN 1 AND 127),
    CONSTRAINT chk_sequence_send_window
        CHECK (send_window_start <> send_window_end),
    CONSTRAINT chk_sequence_archive
        CHECK (
            (status = 'archived' AND archived_at IS NOT NULL)
            OR (status IN ('draft', 'active') AND archived_at IS NULL)
        ),

    UNIQUE KEY uq_sequence_workspace_id (workspace_id, id),
    INDEX idx_sequence_visible (workspace_id, status, visibility, owner_id, updated_at),
    INDEX idx_sequence_owner (owner_id, workspace_id),
    INDEX idx_sequence_created_by (created_by_id, workspace_id),
    INDEX idx_sequence_updated_by (updated_by_id, workspace_id)
) DEFAULT CHARSET=utf8mb4;

CREATE TABLE sequence_version (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id     INT NOT NULL,
    sequence_id      INT NOT NULL,
    version_number   INT UNSIGNED NOT NULL,
    definition_json  MEDIUMTEXT NOT NULL,
    definition_hash  BINARY(32) NOT NULL,
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_sequence_version_number
        CHECK (version_number > 0),
    CONSTRAINT chk_sequence_version_definition
        CHECK (
            JSON_VALID(definition_json) = 1
            AND JSON_TYPE(definition_json) = 'OBJECT'
            AND JSON_CONTAINS_PATH(definition_json, 'all', '$.schemaVersion', '$.steps') = 1
            AND JSON_UNQUOTE(JSON_EXTRACT(definition_json, '$.schemaVersion')) = '1'
            AND JSON_TYPE(JSON_EXTRACT(definition_json, '$.steps')) = 'ARRAY'
            AND OCTET_LENGTH(definition_json) <= 262144
        ),
    CONSTRAINT fk_sequence_version_sequence
        FOREIGN KEY (workspace_id, sequence_id)
        REFERENCES sequence(workspace_id, id)
        ON DELETE CASCADE,

    UNIQUE KEY uq_sequence_version_workspace_id (workspace_id, id),
    UNIQUE KEY uq_sequence_version_number (workspace_id, sequence_id, version_number)
) DEFAULT CHARSET=utf8mb4;

CREATE TABLE sequence_version_publisher (
    workspace_id    INT NOT NULL,
    version_id      BIGINT NOT NULL,
    published_by_id INT NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_sequence_version_publisher_version
        FOREIGN KEY (workspace_id, version_id)
        REFERENCES sequence_version(workspace_id, id)
        ON DELETE CASCADE,

    PRIMARY KEY (workspace_id, version_id),
    INDEX idx_sequence_version_publisher_user (published_by_id, workspace_id)
) DEFAULT CHARSET=utf8mb4;

CREATE TABLE sequence_step (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id    INT NOT NULL,
    sequence_id     INT NOT NULL,
    position        INT UNSIGNED NOT NULL,
    step_type       VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    delay_value     INT UNSIGNED NOT NULL DEFAULT 0,
    delay_unit      VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    advance_policy  VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_sequence_step_type
        CHECK (step_type IN ('send_email', 'call_task', 'general_task', 'wait', 'notify_owner')),
    CONSTRAINT chk_sequence_step_delay_unit
        CHECK (delay_unit IN ('hours', 'business_days')),
    CONSTRAINT chk_sequence_step_advance_policy
        CHECK (advance_policy IN ('automatic', 'manual_completion', 'manual_completion_or_skip')),
    CONSTRAINT chk_sequence_step_delay_value
        CHECK (delay_value <= 8760),
    CONSTRAINT fk_sequence_step_sequence
        FOREIGN KEY (workspace_id, sequence_id)
        REFERENCES sequence(workspace_id, id)
        ON DELETE CASCADE,

    UNIQUE KEY uq_sequence_step_workspace_id (workspace_id, id),
    UNIQUE KEY uq_sequence_step_position (workspace_id, sequence_id, position)
) DEFAULT CHARSET=utf8mb4;

CREATE TABLE sequence_step_content (
    workspace_id  INT NOT NULL,
    step_id       BIGINT NOT NULL,
    locale        VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject       VARCHAR(255) NULL,
    body_text     MEDIUMTEXT NULL,
    body_html     MEDIUMTEXT NULL,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                      ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_sequence_step_content_locale
        CHECK (locale IN ('en', 'ja')),
    CONSTRAINT chk_sequence_step_content_body
        CHECK (subject IS NOT NULL OR body_text IS NOT NULL OR body_html IS NOT NULL),
    CONSTRAINT fk_sequence_step_content_step
        FOREIGN KEY (workspace_id, step_id)
        REFERENCES sequence_step(workspace_id, id)
        ON DELETE CASCADE,

    PRIMARY KEY (workspace_id, step_id, locale)
) DEFAULT CHARSET=utf8mb4;
