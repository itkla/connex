ALTER TABLE rule
    ADD UNIQUE KEY uq_rule_workspace_id (workspace_id, id);

CREATE TABLE workflow (
    id                       INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id             INT NOT NULL,
    legacy_rule_id           INT NULL,
    name                     VARCHAR(128) NOT NULL,
    description              VARCHAR(512) NULL,
    enabled                  BOOLEAN NOT NULL DEFAULT FALSE,
    draft_revision           INT NOT NULL DEFAULT 0,
    draft_record_type        VARCHAR(16) NULL,
    draft_execution_mode     VARCHAR(8) NOT NULL,
    draft_run_as_user_id     INT NULL,
    draft_definition_json    JSON NOT NULL,
    draft_canvas_json        JSON NOT NULL,
    active_version_id        BIGINT NULL,
    created_by_id            INT NULL,
    updated_by_id            INT NULL,
    created_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_workflow_revision CHECK (draft_revision >= 0),
    CONSTRAINT chk_workflow_lifecycle CHECK (
        enabled IN (FALSE, TRUE) AND (enabled = FALSE OR active_version_id IS NOT NULL)
    ),
    CONSTRAINT chk_workflow_draft_mode CHECK (
        draft_execution_mode IN ('user', 'system')
        AND (draft_execution_mode = 'user' OR draft_run_as_user_id IS NULL)
    ),
    CONSTRAINT chk_workflow_draft_definition CHECK (
        JSON_TYPE(draft_definition_json) = 'OBJECT'
        AND JSON_CONTAINS_PATH(draft_definition_json, 'one', '$.schemaVersion') = 1
        AND JSON_TYPE(JSON_EXTRACT(draft_definition_json, '$.schemaVersion')) = 'INTEGER'
        AND JSON_UNQUOTE(JSON_EXTRACT(draft_definition_json, '$.schemaVersion')) = '1'
    ),
    CONSTRAINT chk_workflow_draft_canvas CHECK (JSON_TYPE(draft_canvas_json) = 'OBJECT'),
    CONSTRAINT fk_workflow_legacy_rule FOREIGN KEY (workspace_id, legacy_rule_id)
        REFERENCES rule(workspace_id, id) ON DELETE RESTRICT,
    UNIQUE KEY uq_workflow_workspace_id (workspace_id, id),
    UNIQUE KEY uq_workflow_legacy_rule (workspace_id, legacy_rule_id),
    INDEX idx_workflow_list (workspace_id, updated_at, id),
    INDEX idx_workflow_active_version (workspace_id, id, active_version_id),
    INDEX idx_workflow_draft_run_as (draft_run_as_user_id, workspace_id),
    INDEX idx_workflow_created_by (created_by_id, workspace_id),
    INDEX idx_workflow_updated_by (updated_by_id, workspace_id)
) DEFAULT CHARSET=utf8mb4;

CREATE TABLE workflow_version (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id        INT NOT NULL,
    workflow_id         INT NOT NULL,
    version_number      INT NOT NULL,
    name                VARCHAR(128) NOT NULL,
    description         VARCHAR(512) NULL,
    record_type         VARCHAR(16) NOT NULL,
    trigger_type        VARCHAR(16) NOT NULL,
    trigger_config      JSON NOT NULL,
    condition_json      JSON NULL,
    actions_json        JSON NOT NULL,
    execution_mode      VARCHAR(8) NOT NULL,
    run_as_user_id      INT NULL,
    created_by_id       INT NULL,
    published_by_id     INT NULL,
    definition_json     JSON NOT NULL,
    canvas_json         JSON NOT NULL,
    definition_hash     BINARY(32) NOT NULL,
    published_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_workflow_version_number CHECK (version_number > 0),
    CONSTRAINT chk_workflow_version_trigger CHECK (JSON_TYPE(trigger_config) = 'OBJECT'),
    CONSTRAINT chk_workflow_version_condition CHECK (
        condition_json IS NULL OR JSON_TYPE(condition_json) = 'OBJECT'
    ),
    CONSTRAINT chk_workflow_version_actions CHECK (JSON_TYPE(actions_json) = 'ARRAY'),
    CONSTRAINT chk_workflow_version_mode CHECK (
        execution_mode IN ('user', 'system')
        AND (execution_mode = 'user' OR run_as_user_id IS NULL)
    ),
    CONSTRAINT chk_workflow_version_definition CHECK (
        JSON_TYPE(definition_json) = 'OBJECT'
        AND JSON_CONTAINS_PATH(definition_json, 'one', '$.schemaVersion') = 1
        AND JSON_TYPE(JSON_EXTRACT(definition_json, '$.schemaVersion')) = 'INTEGER'
        AND JSON_UNQUOTE(JSON_EXTRACT(definition_json, '$.schemaVersion')) = '1'
    ),
    CONSTRAINT chk_workflow_version_canvas CHECK (JSON_TYPE(canvas_json) = 'OBJECT'),
    CONSTRAINT fk_workflow_version_workflow FOREIGN KEY (workspace_id, workflow_id)
        REFERENCES workflow(workspace_id, id) ON DELETE CASCADE,
    UNIQUE KEY uq_workflow_version_identity (workspace_id, workflow_id, id),
    UNIQUE KEY uq_workflow_version_number (workspace_id, workflow_id, version_number),
    INDEX idx_workflow_version_run_as (run_as_user_id, workspace_id),
    INDEX idx_workflow_version_created_by (created_by_id, workspace_id),
    INDEX idx_workflow_version_published_by (published_by_id, workspace_id)
) DEFAULT CHARSET=utf8mb4;

ALTER TABLE workflow
    ADD CONSTRAINT fk_workflow_active_version
        FOREIGN KEY (workspace_id, id, active_version_id)
        REFERENCES workflow_version(workspace_id, workflow_id, id) ON DELETE RESTRICT;
