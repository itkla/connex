ALTER TABLE workflow
    ADD COLUMN runtime_owner VARCHAR(16) NOT NULL DEFAULT 'legacy' AFTER enabled,
    ADD COLUMN archived_at DATETIME NULL AFTER runtime_owner,
    ADD CONSTRAINT chk_workflow_runtime_owner
        CHECK (runtime_owner IN ('legacy', 'canonical')),
    ADD CONSTRAINT chk_workflow_canonical_version
        CHECK (runtime_owner = 'legacy' OR active_version_id IS NOT NULL),
    ADD CONSTRAINT chk_workflow_archive_state
        CHECK (archived_at IS NULL OR enabled = FALSE),
    ADD INDEX idx_workflow_visible_list
        (workspace_id, archived_at, updated_at DESC, id DESC),
    ADD INDEX idx_workflow_runtime_dispatch
        (workspace_id, runtime_owner, enabled, archived_at, id);

CREATE TABLE workflow_run (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id            INT NOT NULL,
    workflow_id             INT NOT NULL,
    workflow_version_id     BIGINT NOT NULL,
    status                  VARCHAR(32) NOT NULL,
    trigger_type            VARCHAR(16) NOT NULL,
    trigger_event           VARCHAR(64) NOT NULL,
    trigger_key             VARCHAR(96)
                                CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    record_type             VARCHAR(16) NOT NULL,
    record_id               INT NOT NULL,
    dedupe_key              VARCHAR(128)
                                CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    execution_mode          VARCHAR(8) NOT NULL,
    actor_user_id           INT NULL,
    attribution_user_id     INT NULL,
    current_node_id         VARCHAR(64)
                                CHARACTER SET ascii COLLATE ascii_bin NULL,
    failure_node_id         VARCHAR(64)
                                CHARACTER SET ascii COLLATE ascii_bin NULL,
    failure_code            VARCHAR(32) NULL,
    failure_message         VARCHAR(512) NULL,
    started_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at             DATETIME(6) NULL,
    updated_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_workflow_run_status
        CHECK (status IN (
            'queued', 'running', 'waiting', 'succeeded', 'failed',
            'skipped', 'cancelled', 'intervention_required'
        )),
    CONSTRAINT chk_workflow_run_trigger
        CHECK (trigger_type IN ('entity_change', 'schedule', 'manual')),
    CONSTRAINT chk_workflow_run_mode
        CHECK (execution_mode IN ('user', 'system')),
    CONSTRAINT chk_workflow_run_record
        CHECK (record_id > 0),
    CONSTRAINT chk_workflow_run_actor
        CHECK (
            (actor_user_id IS NULL OR actor_user_id > 0)
            AND (attribution_user_id IS NULL OR attribution_user_id > 0)
        ),
    CONSTRAINT chk_workflow_run_timing
        CHECK (finished_at IS NULL OR finished_at >= started_at),
    CONSTRAINT chk_workflow_run_terminal
        CHECK (
            (
                status IN ('queued', 'running', 'waiting')
                AND finished_at IS NULL
                AND failure_node_id IS NULL
                AND failure_code IS NULL
                AND failure_message IS NULL
            )
            OR (
                status IN ('succeeded', 'skipped', 'cancelled')
                AND finished_at IS NOT NULL
                AND failure_node_id IS NULL
                AND failure_code IS NULL
                AND failure_message IS NULL
            )
            OR (
                status IN ('failed', 'intervention_required')
                AND finished_at IS NOT NULL
                AND failure_node_id IS NOT NULL
                AND failure_code IS NOT NULL
                AND failure_message IS NOT NULL
            )
        ),

    CONSTRAINT fk_workflow_run_workflow
        FOREIGN KEY (workspace_id, workflow_id)
        REFERENCES workflow(workspace_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_workflow_run_version
        FOREIGN KEY (workspace_id, workflow_id, workflow_version_id)
        REFERENCES workflow_version(workspace_id, workflow_id, id)
        ON DELETE RESTRICT,

    UNIQUE KEY uq_workflow_run_workspace_id
        (workspace_id, id),
    UNIQUE KEY uq_workflow_run_dedupe
        (workspace_id, workflow_id, dedupe_key),
    INDEX idx_workflow_run_list
        (workspace_id, workflow_id, started_at DESC, id DESC),
    INDEX idx_workflow_run_status
        (workspace_id, status, started_at DESC, id DESC),
    INDEX idx_workflow_run_version
        (workspace_id, workflow_id, workflow_version_id),
    INDEX idx_workflow_run_trigger
        (workspace_id, workflow_id, trigger_key, status, id)
) DEFAULT CHARSET=utf8mb4;

CREATE TABLE workflow_step_run (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id            INT NOT NULL,
    workflow_run_id         BIGINT NOT NULL,
    sequence_number         SMALLINT UNSIGNED NOT NULL,
    node_id                 VARCHAR(64)
                                CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    node_type               VARCHAR(16) NOT NULL,
    status                  VARCHAR(16) NOT NULL,
    attempt_count           TINYINT UNSIGNED NOT NULL DEFAULT 1,
    selected_outcome        VARCHAR(8) NULL,
    selected_edge_id        VARCHAR(64)
                                CHARACTER SET ascii COLLATE ascii_bin NULL,
    next_node_id            VARCHAR(64)
                                CHARACTER SET ascii COLLATE ascii_bin NULL,
    failure_code            VARCHAR(32) NULL,
    failure_message         VARCHAR(512) NULL,
    started_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at             DATETIME(6) NULL,

    CONSTRAINT chk_workflow_step_sequence
        CHECK (sequence_number <= 49),
    CONSTRAINT chk_workflow_step_type
        CHECK (node_type IN ('trigger', 'condition', 'action', 'delay', 'end')),
    CONSTRAINT chk_workflow_step_status
        CHECK (status IN (
            'queued', 'running', 'waiting', 'succeeded', 'failed', 'skipped', 'cancelled'
        )),
    CONSTRAINT chk_workflow_step_attempts
        CHECK (attempt_count BETWEEN 1 AND 10),
    CONSTRAINT chk_workflow_step_timing
        CHECK (finished_at IS NULL OR finished_at >= started_at),
    CONSTRAINT chk_workflow_step_terminal
        CHECK (
            (
                status IN ('queued', 'running', 'waiting')
                AND finished_at IS NULL
                AND failure_code IS NULL
                AND failure_message IS NULL
            )
            OR (
                status IN ('succeeded', 'skipped', 'cancelled')
                AND finished_at IS NOT NULL
                AND failure_code IS NULL
                AND failure_message IS NULL
            )
            OR (
                status = 'failed'
                AND finished_at IS NOT NULL
                AND failure_code IS NOT NULL
                AND failure_message IS NOT NULL
            )
        ),
    CONSTRAINT chk_workflow_step_transition
        CHECK (
            (
                status <> 'succeeded'
                AND selected_outcome IS NULL
                AND selected_edge_id IS NULL
                AND next_node_id IS NULL
            )
            OR (
                status = 'succeeded'
                AND node_type = 'end'
                AND selected_outcome IS NULL
                AND selected_edge_id IS NULL
                AND next_node_id IS NULL
            )
            OR (
                status = 'succeeded'
                AND node_type = 'condition'
                AND selected_outcome IN ('yes', 'no')
                AND selected_edge_id IS NOT NULL
                AND next_node_id IS NOT NULL
            )
            OR (
                status = 'succeeded'
                AND node_type IN ('trigger', 'action', 'delay')
                AND selected_outcome = 'next'
                AND selected_edge_id IS NOT NULL
                AND next_node_id IS NOT NULL
            )
        ),

    CONSTRAINT fk_workflow_step_run
        FOREIGN KEY (workspace_id, workflow_run_id)
        REFERENCES workflow_run(workspace_id, id)
        ON DELETE RESTRICT,

    UNIQUE KEY uq_workflow_step_run_identity
        (workspace_id, workflow_run_id, id),
    UNIQUE KEY uq_workflow_step_node
        (workspace_id, workflow_run_id, node_id),
    UNIQUE KEY uq_workflow_step_sequence
        (workspace_id, workflow_run_id, sequence_number)
) DEFAULT CHARSET=utf8mb4;
