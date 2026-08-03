ALTER TABLE workflow
    ADD COLUMN runtime_generation BIGINT UNSIGNED NOT NULL DEFAULT 0
        AFTER archived_at;

CREATE TABLE workflow_runtime_workspace (
    workspace_id       INT NOT NULL PRIMARY KEY,
    next_queue         VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin
                           NOT NULL DEFAULT 'trigger',
    last_claimed_at    DATETIME(6) NULL,
    created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                           ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_workflow_runtime_workspace_queue
        CHECK (next_queue IN ('trigger', 'run'))
) DEFAULT CHARSET=utf8mb4;

CREATE TABLE workflow_trigger_outbox (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id                INT NOT NULL,
    workflow_id                 INT NOT NULL,
    workflow_version_id         BIGINT NOT NULL,
    workflow_runtime_generation BIGINT UNSIGNED NOT NULL,
    trigger_type                VARCHAR(16)
                                    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trigger_event               VARCHAR(64) NOT NULL,
    trigger_key                 VARCHAR(96)
                                    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    record_type                 VARCHAR(16) NOT NULL,
    record_id                   INT NULL,
    occurred_at                 DATETIME(6) NULL,
    record_scan_after_id        INT NOT NULL DEFAULT 0,
    record_scan_upper_id        INT NOT NULL DEFAULT 0,
    dedupe_key                  VARCHAR(128)
                                    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status                      VARCHAR(16)
                                    CHARACTER SET ascii COLLATE ascii_bin
                                    NOT NULL DEFAULT 'pending',
    available_at                DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_owner                 CHAR(36)
                                    CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until                 DATETIME(6) NULL,
    delivery_attempt_count      TINYINT UNSIGNED NOT NULL DEFAULT 0,
    last_error_code             VARCHAR(64)
                                    CHARACTER SET ascii COLLATE ascii_bin NULL,
    completed_at                DATETIME(6) NULL,
    created_at                  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                    ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_workflow_trigger_outbox_attempts
        CHECK (delivery_attempt_count <= 8),
    CONSTRAINT chk_workflow_trigger_outbox_scan
        CHECK (
            record_scan_after_id >= 0
            AND record_scan_upper_id >= 0
            AND record_scan_after_id <= record_scan_upper_id
        ),
    CONSTRAINT chk_workflow_trigger_outbox_source
        CHECK (
            (
                trigger_type = 'entity_change'
                AND record_id IS NOT NULL
                AND record_id > 0
                AND occurred_at IS NOT NULL
                AND record_scan_after_id = 0
                AND record_scan_upper_id = 0
            )
            OR
            (
                trigger_type = 'schedule'
                AND record_id IS NULL
                AND occurred_at IS NULL
            )
        ),
    CONSTRAINT chk_workflow_trigger_outbox_state
        CHECK (
            (
                status = 'pending'
                AND lease_owner IS NULL
                AND lease_until IS NULL
                AND completed_at IS NULL
            )
            OR
            (
                status = 'leased'
                AND lease_owner IS NOT NULL
                AND lease_until IS NOT NULL
                AND completed_at IS NULL
            )
            OR
            (
                status IN ('completed', 'invalidated')
                AND lease_owner IS NULL
                AND lease_until IS NULL
                AND completed_at IS NOT NULL
                AND last_error_code IS NULL
            )
            OR
            (
                status = 'dead'
                AND lease_owner IS NULL
                AND lease_until IS NULL
                AND completed_at IS NOT NULL
                AND last_error_code IS NOT NULL
            )
        ),

    CONSTRAINT fk_workflow_trigger_outbox_version
        FOREIGN KEY (workspace_id, workflow_id, workflow_version_id)
        REFERENCES workflow_version(workspace_id, workflow_id, id)
        ON DELETE RESTRICT,

    UNIQUE KEY uq_workflow_trigger_outbox_dedupe
        (workspace_id, workflow_id, dedupe_key),
    INDEX idx_workflow_trigger_outbox_due
        (workspace_id, status, available_at, lease_until, id),
    INDEX idx_workflow_trigger_outbox_workflow
        (workspace_id, workflow_id, created_at, id)
) DEFAULT CHARSET=utf8mb4;

-- V143 could leave an immediate-runtime row nonterminal after a process crash. Preserve that
-- evidence as an operator-visible intervention before durable lease invariants are installed.
UPDATE workflow_step_run wsr
JOIN workflow_run wr
  ON wr.workspace_id = wsr.workspace_id
 AND wr.id = wsr.workflow_run_id
SET wsr.status = 'failed',
    wsr.selected_outcome = NULL,
    wsr.selected_edge_id = NULL,
    wsr.next_node_id = NULL,
    wsr.failure_code = 'runtime_upgrade_required',
    wsr.failure_message = 'The workflow step predates durable runtime recovery.',
    wsr.finished_at = GREATEST(wsr.started_at, CURRENT_TIMESTAMP(6))
WHERE wr.status IN ('running', 'waiting')
  AND wsr.status IN ('queued', 'running', 'waiting');

UPDATE workflow_run
SET status = 'intervention_required',
    failure_node_id = COALESCE(current_node_id, 'upgrade'),
    failure_code = 'runtime_upgrade_required',
    failure_message = 'The workflow run predates durable runtime recovery.',
    finished_at = GREATEST(started_at, CURRENT_TIMESTAMP(6))
WHERE status IN ('running', 'waiting');

ALTER TABLE workflow_run
    ADD COLUMN trigger_outbox_id BIGINT NULL AFTER dedupe_key,
    ADD COLUMN wait_kind VARCHAR(8)
                             CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER current_node_id,
    ADD COLUMN resume_at DATETIME(6) NULL AFTER wait_kind,
    ADD COLUMN lease_owner CHAR(36)
                               CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER resume_at,
    ADD COLUMN lease_until DATETIME(6) NULL AFTER lease_owner,
    ADD COLUMN dispatch_count SMALLINT UNSIGNED NOT NULL DEFAULT 0
        AFTER lease_until,
    ADD COLUMN cancel_requested_at DATETIME(6) NULL AFTER dispatch_count,
    ADD CONSTRAINT chk_workflow_run_trigger_outbox
        CHECK (trigger_outbox_id IS NULL OR trigger_outbox_id > 0),
    ADD CONSTRAINT chk_workflow_run_wait_kind
        CHECK (wait_kind IS NULL OR wait_kind IN ('delay', 'retry')),
    ADD CONSTRAINT chk_workflow_run_dispatch_count
        CHECK (dispatch_count <= 256),
    ADD CONSTRAINT chk_workflow_run_runtime_state
        CHECK (
            (
                status = 'running'
                AND lease_owner IS NOT NULL
                AND lease_until IS NOT NULL
                AND (
                    (wait_kind IS NULL AND resume_at IS NULL)
                    OR (wait_kind IS NOT NULL AND resume_at IS NOT NULL)
                )
            )
            OR
            (
                status = 'waiting'
                AND wait_kind IS NOT NULL
                AND resume_at IS NOT NULL
                AND lease_owner IS NULL
                AND lease_until IS NULL
            )
            OR
            (
                status NOT IN ('running', 'waiting')
                AND wait_kind IS NULL
                AND resume_at IS NULL
                AND lease_owner IS NULL
                AND lease_until IS NULL
            )
        ),
    ADD CONSTRAINT chk_workflow_run_cancel_request
        CHECK (
            cancel_requested_at IS NULL
            OR status IN ('running', 'cancelled')
        ),
    ADD INDEX idx_workflow_run_claim
        (workspace_id, status, resume_at, lease_until, id),
    ADD INDEX idx_workflow_run_cancel
        (workspace_id, status, cancel_requested_at, id),
    ADD INDEX idx_workflow_run_outbox
        (workspace_id, trigger_outbox_id, id);

ALTER TABLE workflow_step_run
    ADD COLUMN retry_safety VARCHAR(16)
                                CHARACTER SET ascii COLLATE ascii_bin
                                NOT NULL DEFAULT 'none'
        AFTER attempt_count,
    ADD CONSTRAINT chk_workflow_step_run_retry_safety
        CHECK (retry_safety IN ('none', 'transactional', 'deduplicated')),
    ADD INDEX idx_workflow_step_run_state
        (workspace_id, workflow_run_id, status, id);

CREATE TABLE workflow_step_attempt (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id          INT NOT NULL,
    workflow_run_id       BIGINT NOT NULL,
    workflow_step_run_id  BIGINT NOT NULL,
    attempt_number        TINYINT UNSIGNED NOT NULL,
    retry_safety          VARCHAR(16)
                              CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status                VARCHAR(16)
                              CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    error_code            VARCHAR(64)
                              CHARACTER SET ascii COLLATE ascii_bin NULL,
    started_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at           DATETIME(6) NULL,

    CONSTRAINT chk_workflow_step_attempt_number
        CHECK (attempt_number BETWEEN 1 AND 3),
    CONSTRAINT chk_workflow_step_attempt_safety
        CHECK (retry_safety IN ('none', 'transactional', 'deduplicated')),
    CONSTRAINT chk_workflow_step_attempt_state
        CHECK (
            (
                status = 'running'
                AND error_code IS NULL
                AND finished_at IS NULL
            )
            OR
            (
                status = 'succeeded'
                AND error_code IS NULL
                AND finished_at IS NOT NULL
            )
            OR
            (
                status IN ('failed', 'abandoned')
                AND error_code IS NOT NULL
                AND finished_at IS NOT NULL
            )
        ),

    CONSTRAINT fk_workflow_step_attempt_step
        FOREIGN KEY (workspace_id, workflow_run_id, workflow_step_run_id)
        REFERENCES workflow_step_run(workspace_id, workflow_run_id, id)
        ON DELETE RESTRICT,

    UNIQUE KEY uq_workflow_step_attempt_number
        (workspace_id, workflow_run_id, workflow_step_run_id, attempt_number),
    INDEX idx_workflow_step_attempt_run
        (workspace_id, workflow_run_id, started_at, id)
) DEFAULT CHARSET=utf8mb4;
