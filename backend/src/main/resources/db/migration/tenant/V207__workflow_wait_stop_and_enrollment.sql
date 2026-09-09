ALTER TABLE workflow_run
    ADD COLUMN status_reason VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL AFTER status;

ALTER TABLE workflow_run
    DROP CHECK chk_workflow_run_status,
    DROP CHECK chk_workflow_run_terminal,
    DROP CHECK chk_workflow_run_wait_kind,
    DROP CHECK chk_workflow_run_runtime_state,
    ADD CONSTRAINT chk_workflow_run_status
        CHECK (status IN (
            'queued', 'running', 'waiting', 'succeeded', 'failed',
            'skipped', 'stopped', 'cancelled', 'intervention_required'
        )),
    ADD CONSTRAINT chk_workflow_run_status_reason
        CHECK (
            (status = 'stopped' AND status_reason IS NOT NULL)
            OR status = 'skipped'
            OR (status NOT IN ('skipped', 'stopped') AND status_reason IS NULL)
        ),
    ADD CONSTRAINT chk_workflow_run_terminal
        CHECK (
            (
                status IN ('queued', 'running', 'waiting')
                AND finished_at IS NULL
                AND failure_node_id IS NULL
                AND failure_code IS NULL
                AND failure_message IS NULL
            )
            OR (
                status IN ('succeeded', 'skipped', 'stopped', 'cancelled')
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
    ADD CONSTRAINT chk_workflow_run_wait_kind
        CHECK (wait_kind IS NULL OR wait_kind IN ('delay', 'retry', 'event')),
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
            OR (
                status = 'waiting'
                AND wait_kind IS NOT NULL
                AND resume_at IS NOT NULL
                AND lease_owner IS NULL
                AND lease_until IS NULL
            )
            OR (
                status NOT IN ('running', 'waiting')
                AND wait_kind IS NULL
                AND resume_at IS NULL
                AND lease_owner IS NULL
                AND lease_until IS NULL
            )
        ),
    ADD INDEX idx_workflow_run_enrollment
        (workspace_id, workflow_id, record_type, record_id, status, started_at, id);

ALTER TABLE workflow_step_run
    DROP CHECK chk_workflow_step_type,
    DROP CHECK chk_workflow_step_transition,
    MODIFY COLUMN selected_outcome VARCHAR(16) NULL,
    ADD CONSTRAINT chk_workflow_step_type
        CHECK (node_type IN ('trigger', 'condition', 'action', 'delay', 'wait', 'end')),
    ADD CONSTRAINT chk_workflow_step_transition
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
            OR (
                status = 'succeeded'
                AND node_type = 'wait'
                AND selected_outcome IN ('completed', 'timeout')
                AND selected_edge_id IS NOT NULL
                AND next_node_id IS NOT NULL
            )
        );

CREATE TABLE task_completion_event (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id   INT NOT NULL,
    task_id        INT NOT NULL,
    occurred_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    UNIQUE KEY uq_task_completion_event_workspace_id (workspace_id, id),
    INDEX idx_task_completion_event_task
        (workspace_id, task_id, occurred_at, id)
) DEFAULT CHARSET=utf8mb4;

CREATE TABLE workflow_event_wait (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id          INT NOT NULL,
    workflow_run_id       BIGINT NOT NULL,
    workflow_step_run_id  BIGINT NOT NULL,
    source_step_run_id    BIGINT NOT NULL,
    node_id               VARCHAR(64)
                              CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_task_id        INT NOT NULL,
    event_type            VARCHAR(32)
                              CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    timeout_at            DATETIME(6) NOT NULL,
    resolution            VARCHAR(16)
                              CHARACTER SET ascii COLLATE ascii_bin NULL,
    matched_event_id      BIGINT NULL,
    resolved_at           DATETIME(6) NULL,
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_workflow_event_wait_task CHECK (source_task_id > 0),
    CONSTRAINT chk_workflow_event_wait_event CHECK (event_type = 'task.completed'),
    CONSTRAINT chk_workflow_event_wait_resolution CHECK (
        (resolution IS NULL AND matched_event_id IS NULL AND resolved_at IS NULL)
        OR (resolution = 'completed' AND matched_event_id IS NOT NULL AND resolved_at IS NOT NULL)
        OR (resolution IN ('timeout', 'cancelled', 'stopped')
            AND matched_event_id IS NULL AND resolved_at IS NOT NULL)
    ),
    CONSTRAINT fk_workflow_event_wait_step
        FOREIGN KEY (workspace_id, workflow_run_id, workflow_step_run_id)
        REFERENCES workflow_step_run(workspace_id, workflow_run_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_workflow_event_wait_source_step
        FOREIGN KEY (workspace_id, workflow_run_id, source_step_run_id)
        REFERENCES workflow_step_run(workspace_id, workflow_run_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_workflow_event_wait_completion
        FOREIGN KEY (workspace_id, matched_event_id)
        REFERENCES task_completion_event(workspace_id, id)
        ON DELETE RESTRICT,

    UNIQUE KEY uq_workflow_event_wait_node
        (workspace_id, workflow_run_id, node_id),
    INDEX idx_workflow_event_wait_task
        (workspace_id, source_task_id, resolution, timeout_at, id)
) DEFAULT CHARSET=utf8mb4;
