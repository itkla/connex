ALTER TABLE workflow
    ADD COLUMN intake_paused_at DATETIME(6) NULL AFTER archived_at,
    ADD COLUMN intake_paused_by_id INT NULL AFTER intake_paused_at,
    ADD INDEX idx_workflow_intake_gate
        (workspace_id, enabled, archived_at, intake_paused_at, id),
    ADD INDEX idx_workflow_intake_paused_by
        (intake_paused_by_id, workspace_id);

CREATE TABLE workflow_recipe_origin (
    workspace_id       INT NOT NULL,
    workflow_id        INT NOT NULL,
    recipe_key         VARCHAR(64)
                           CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recipe_version     INT UNSIGNED NOT NULL,
    template_hash      BINARY(32) NOT NULL,
    installed_by_id    INT NULL,
    installed_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_workflow_recipe_origin_version
        CHECK (recipe_version > 0),
    CONSTRAINT fk_workflow_recipe_origin_workflow
        FOREIGN KEY (workspace_id, workflow_id)
        REFERENCES workflow(workspace_id, id)
        ON DELETE RESTRICT,

    PRIMARY KEY (workspace_id, workflow_id),
    INDEX idx_workflow_recipe_origin_recipe
        (workspace_id, recipe_key, recipe_version),
    INDEX idx_workflow_recipe_origin_installer
        (installed_by_id, workspace_id)
) DEFAULT CHARSET=utf8mb4;

CREATE TABLE workflow_invocation (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id          INT NOT NULL,
    workflow_id           INT NOT NULL,
    workflow_version_id   BIGINT NOT NULL,
    requested_by_id       INT NULL,
    scope_kind            VARCHAR(32)
                              CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resolved_scope_kind   VARCHAR(32)
                              CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_surface        VARCHAR(24)
                              CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    record_type           VARCHAR(16)
                              CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_token_hash      BINARY(32) NOT NULL,
    scope_hash            BINARY(32) NOT NULL,
    confirmation_key      BINARY(16) NULL,
    scope_contract_json   JSON NOT NULL,
    exact_count           SMALLINT UNSIGNED NOT NULL,
    ready_count           SMALLINT UNSIGNED NOT NULL,
    skipped_count         SMALLINT UNSIGNED NOT NULL,
    status                VARCHAR(16)
                              CHARACTER SET ascii COLLATE ascii_bin
                              NOT NULL DEFAULT 'prepared',
    expires_at            DATETIME(6) NOT NULL,
    confirmed_at          DATETIME(6) NULL,
    completed_at          DATETIME(6) NULL,
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                              ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_workflow_invocation_scope_kind
        CHECK (scope_kind IN (
            'single_record', 'page_selection', 'explicit_selection',
            'filter_match', 'smart_segment', 'saved_view',
            'search_snapshot', 'command_palette'
        )),
    CONSTRAINT chk_workflow_invocation_resolved_scope_kind
        CHECK (
            resolved_scope_kind IN (
                'single_record', 'page_selection', 'explicit_selection',
                'filter_match', 'smart_segment', 'saved_view', 'search_snapshot'
            )
            AND (
                scope_kind = 'command_palette'
                OR scope_kind = resolved_scope_kind
            )
        ),
    CONSTRAINT chk_workflow_invocation_surface
        CHECK (source_surface IN (
            'record', 'record_list', 'saved_view', 'search', 'command_palette'
        )),
    CONSTRAINT chk_workflow_invocation_record_type
        CHECK (record_type IN ('company', 'person', 'deal')),
    CONSTRAINT chk_workflow_invocation_status
        CHECK (status IN (
            'prepared', 'confirmed', 'running', 'succeeded', 'partial',
            'failed', 'cancelled', 'expired'
        )),
    CONSTRAINT chk_workflow_invocation_scope
        CHECK (
            JSON_TYPE(scope_contract_json) = 'OBJECT'
            AND OCTET_LENGTH(scope_contract_json) <= 16384
        ),
    CONSTRAINT chk_workflow_invocation_counts
        CHECK (
            exact_count <= 1000
            AND ready_count <= exact_count
            AND skipped_count <= exact_count
            AND ready_count + skipped_count = exact_count
        ),
    CONSTRAINT chk_workflow_invocation_timing
        CHECK (
            expires_at >= created_at
            AND (confirmed_at IS NULL OR confirmed_at >= created_at)
            AND (completed_at IS NULL OR completed_at >= created_at)
        ),
    CONSTRAINT chk_workflow_invocation_lifecycle
        CHECK (
            (
                status = 'prepared'
                AND confirmation_key IS NULL
                AND confirmed_at IS NULL
                AND completed_at IS NULL
            )
            OR (
                status IN ('confirmed', 'running')
                AND confirmation_key IS NOT NULL
                AND confirmed_at IS NOT NULL
                AND completed_at IS NULL
            )
            OR (
                status IN ('succeeded', 'partial', 'failed')
                AND confirmation_key IS NOT NULL
                AND confirmed_at IS NOT NULL
                AND completed_at IS NOT NULL
            )
            OR (
                status = 'cancelled'
                AND completed_at IS NOT NULL
                AND (
                    (
                        confirmation_key IS NULL
                        AND confirmed_at IS NULL
                    )
                    OR (
                        confirmation_key IS NOT NULL
                        AND confirmed_at IS NOT NULL
                    )
                )
            )
            OR (
                status = 'expired'
                AND confirmation_key IS NULL
                AND confirmed_at IS NULL
                AND completed_at IS NOT NULL
            )
        ),
    CONSTRAINT fk_workflow_invocation_version
        FOREIGN KEY (workspace_id, workflow_id, workflow_version_id)
        REFERENCES workflow_version(workspace_id, workflow_id, id)
        ON DELETE RESTRICT,

    UNIQUE KEY uq_workflow_invocation_workspace_id
        (workspace_id, id),
    UNIQUE KEY uq_workflow_invocation_token
        (workspace_id, scope_token_hash),
    UNIQUE KEY uq_workflow_invocation_confirmation
        (workspace_id, requested_by_id, confirmation_key),
    INDEX idx_workflow_invocation_list
        (workspace_id, workflow_id, created_at DESC, id DESC),
    INDEX idx_workflow_invocation_status
        (workspace_id, status, created_at, id),
    INDEX idx_workflow_invocation_expiry
        (workspace_id, status, expires_at, id),
    INDEX idx_workflow_invocation_requester
        (requested_by_id, workspace_id)
) DEFAULT CHARSET=utf8mb4;

CREATE TABLE workflow_invocation_record (
    workspace_id               INT NOT NULL,
    invocation_id              BIGINT NOT NULL,
    ordinal                    SMALLINT UNSIGNED NOT NULL,
    record_id                  INT NOT NULL,
    preview_status             VARCHAR(16)
                                   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preview_reason_code        VARCHAR(32)
                                   CHARACTER SET ascii COLLATE ascii_bin NULL,
    execution_status           VARCHAR(24)
                                   CHARACTER SET ascii COLLATE ascii_bin
                                   NOT NULL DEFAULT 'pending',
    execution_failure_category VARCHAR(24)
                                   CHARACTER SET ascii COLLATE ascii_bin NULL,
    workflow_run_id            BIGINT NULL,
    created_at                 DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                 DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                   ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_workflow_invocation_record_ordinal
        CHECK (ordinal <= 999),
    CONSTRAINT chk_workflow_invocation_record_id
        CHECK (record_id > 0),
    CONSTRAINT chk_workflow_invocation_record_preview
        CHECK (
            (preview_status = 'ready' AND preview_reason_code IS NULL)
            OR (
                preview_status = 'skipped'
                AND preview_reason_code IN (
                    'record_not_visible', 'record_not_found',
                    'record_type_mismatch', 'action_permission_missing',
                    'actor_unavailable', 'actor_inactive',
                    'configuration_missing'
                )
            )
        ),
    CONSTRAINT chk_workflow_invocation_record_execution
        CHECK (execution_status IN (
            'pending', 'queued', 'running', 'waiting', 'succeeded', 'failed',
            'cancelled', 'skipped', 'intervention_required'
        )),
    CONSTRAINT chk_workflow_invocation_record_failure
        CHECK (execution_failure_category IS NULL OR execution_failure_category IN (
            'actor', 'permission', 'reference', 'retry',
            'configuration', 'execution'
        )),
    CONSTRAINT fk_workflow_invocation_record_invocation
        FOREIGN KEY (workspace_id, invocation_id)
        REFERENCES workflow_invocation(workspace_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_workflow_invocation_record_run
        FOREIGN KEY (workspace_id, workflow_run_id)
        REFERENCES workflow_run(workspace_id, id)
        ON DELETE RESTRICT,

    PRIMARY KEY (workspace_id, invocation_id, ordinal),
    UNIQUE KEY uq_workflow_invocation_record_identity
        (workspace_id, invocation_id, record_id),
    UNIQUE KEY uq_workflow_invocation_record_run
        (workspace_id, workflow_run_id),
    INDEX idx_workflow_invocation_record_outcome
        (workspace_id, invocation_id, execution_status, ordinal)
) DEFAULT CHARSET=utf8mb4;

CREATE TABLE workflow_intervention (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id          INT NOT NULL,
    workflow_run_id       BIGINT NOT NULL,
    workflow_step_run_id  BIGINT NULL,
    intervention_key      BINARY(32) NOT NULL,
    category              VARCHAR(24)
                              CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code           VARCHAR(64)
                              CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_user_id         INT NULL,
    status                VARCHAR(16)
                              CHARACTER SET ascii COLLATE ascii_bin
                              NOT NULL DEFAULT 'open',
    source_version        INT UNSIGNED NOT NULL DEFAULT 0,
    resolved_at           DATETIME(6) NULL,
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                              ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_workflow_intervention_category
        CHECK (category IN (
            'actor', 'permission', 'reference', 'retry',
            'configuration', 'execution'
        )),
    CONSTRAINT chk_workflow_intervention_status
        CHECK (status IN ('open', 'resolved', 'cancelled')),
    CONSTRAINT chk_workflow_intervention_resolution
        CHECK (
            (status = 'open' AND resolved_at IS NULL)
            OR (status IN ('resolved', 'cancelled') AND resolved_at IS NOT NULL)
        ),
    CONSTRAINT fk_workflow_intervention_run
        FOREIGN KEY (workspace_id, workflow_run_id)
        REFERENCES workflow_run(workspace_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_workflow_intervention_step
        FOREIGN KEY (workspace_id, workflow_run_id, workflow_step_run_id)
        REFERENCES workflow_step_run(workspace_id, workflow_run_id, id)
        ON DELETE RESTRICT,

    UNIQUE KEY uq_workflow_intervention_workspace_id
        (workspace_id, id),
    UNIQUE KEY uq_workflow_intervention_key
        (workspace_id, intervention_key),
    INDEX idx_workflow_intervention_owner
        (workspace_id, owner_user_id, status, updated_at DESC, id DESC),
    INDEX idx_workflow_intervention_owner_offboarding
        (owner_user_id, workspace_id),
    INDEX idx_workflow_intervention_run
        (workspace_id, workflow_run_id, status, id)
) DEFAULT CHARSET=utf8mb4;

INSERT INTO workflow_intervention (
    workspace_id, workflow_run_id, workflow_step_run_id, intervention_key,
    category, reason_code, status, source_version
)
SELECT wr.workspace_id,
       wr.id,
       wsr.id,
       UNHEX(SHA2(CONCAT(
           wr.workspace_id, ':', wr.id, ':', COALESCE(wr.failure_node_id, 'null')
       ), 256)),
       CASE
         WHEN wr.failure_code IN ('actor_unavailable', 'actor_inactive') THEN 'actor'
         WHEN wr.failure_code IN ('permission_denied', 'action_permission_missing') THEN 'permission'
         WHEN wr.failure_code IN ('reference_unavailable', 'record_unavailable') THEN 'reference'
         WHEN wr.failure_code IN (
           'retry_exhausted', 'retry_not_safe', 'transient_database_failure'
         ) THEN 'retry'
         WHEN wr.failure_code IN (
           'configuration_invalid', 'invalid_action_config',
           'definition_corrupt', 'definition_invalid'
         ) THEN 'configuration'
         ELSE 'execution'
       END,
       wr.failure_code,
       'open',
       0
FROM workflow_run wr
LEFT JOIN workflow_step_run wsr
  ON wsr.workspace_id = wr.workspace_id
 AND wsr.workflow_run_id = wr.id
 AND wsr.node_id = wr.failure_node_id
WHERE wr.status = 'intervention_required'
  AND wr.failure_code IS NOT NULL;
