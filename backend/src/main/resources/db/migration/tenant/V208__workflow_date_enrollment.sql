ALTER TABLE workflow
    DROP CHECK chk_workflow_draft_definition,
    ADD CONSTRAINT chk_workflow_draft_definition CHECK (
        JSON_VALID(draft_definition_json) = 1
        AND JSON_TYPE(draft_definition_json) = 'OBJECT'
        AND JSON_CONTAINS_PATH(draft_definition_json, 'one', '$.schemaVersion') = 1
        AND JSON_TYPE(JSON_EXTRACT(draft_definition_json, '$.schemaVersion')) = 'INTEGER'
        AND JSON_UNQUOTE(JSON_EXTRACT(draft_definition_json, '$.schemaVersion')) IN ('1', '2')
        AND OCTET_LENGTH(draft_definition_json) <= 65536
    );

ALTER TABLE workflow_version
    DROP CHECK chk_workflow_version_definition,
    ADD CONSTRAINT chk_workflow_version_definition CHECK (
        JSON_VALID(definition_json) = 1
        AND JSON_TYPE(definition_json) = 'OBJECT'
        AND JSON_CONTAINS_PATH(definition_json, 'one', '$.schemaVersion') = 1
        AND JSON_TYPE(JSON_EXTRACT(definition_json, '$.schemaVersion')) = 'INTEGER'
        AND JSON_UNQUOTE(JSON_EXTRACT(definition_json, '$.schemaVersion')) IN ('1', '2')
        AND OCTET_LENGTH(definition_json) <= 65536
    );

ALTER TABLE deal
    ADD INDEX idx_deal_expected_close_date
        (workspace_id, expected_close_date, id);

CREATE TABLE workflow_date_enrollment (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id                INT NOT NULL,
    workflow_id                 INT NOT NULL,
    workflow_version_id         BIGINT NOT NULL,
    workflow_runtime_generation BIGINT UNSIGNED NOT NULL,
    record_type                 VARCHAR(16) NOT NULL,
    record_id                   INT NOT NULL,
    date_field                  VARCHAR(48)
                                    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_date                 DATE NOT NULL,
    scheduled_local_date        DATE NOT NULL,
    due_at                      DATETIME(6) NOT NULL,
    state                       VARCHAR(16)
                                    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    workflow_run_id             BIGINT NULL,
    queued_at                   DATETIME(6) NULL,
    resolved_at                 DATETIME(6) NULL,
    created_at                  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                    ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_workflow_date_enrollment_record CHECK (
        record_type = 'deal'
        AND record_id > 0
        AND date_field = 'expectedCloseDate'
    ),
    CONSTRAINT chk_workflow_date_enrollment_state CHECK (
        (state = 'planned'
            AND workflow_run_id IS NULL
            AND queued_at IS NULL
            AND resolved_at IS NULL)
        OR (state = 'queued'
            AND workflow_run_id IS NULL
            AND queued_at IS NOT NULL
            AND resolved_at IS NULL)
        OR (state = 'enrolled'
            AND workflow_run_id IS NOT NULL
            AND queued_at IS NOT NULL
            AND resolved_at IS NOT NULL)
        OR (state IN ('superseded', 'missed')
            AND workflow_run_id IS NULL
            AND resolved_at IS NOT NULL)
    ),
    CONSTRAINT fk_workflow_date_enrollment_version
        FOREIGN KEY (workspace_id, workflow_id, workflow_version_id)
        REFERENCES workflow_version(workspace_id, workflow_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_workflow_date_enrollment_run
        FOREIGN KEY (workspace_id, workflow_run_id)
        REFERENCES workflow_run(workspace_id, id)
        ON DELETE RESTRICT,

    UNIQUE KEY uq_workflow_date_enrollment_workspace_id (workspace_id, id),
    UNIQUE KEY uq_workflow_date_enrollment_period
        (workspace_id, workflow_id, record_type, record_id, date_field, source_date),
    INDEX idx_workflow_date_enrollment_due
        (workspace_id, state, due_at, id),
    INDEX idx_workflow_date_enrollment_record
        (workspace_id, workflow_id, record_type, record_id, state, id)
) DEFAULT CHARSET=utf8mb4;

ALTER TABLE workflow_trigger_outbox
    ADD COLUMN workflow_date_enrollment_id BIGINT NULL AFTER workflow_runtime_generation,
    DROP CHECK chk_workflow_trigger_outbox_source,
    ADD CONSTRAINT chk_workflow_trigger_outbox_source CHECK (
        (
            trigger_type = 'entity_change'
            AND workflow_date_enrollment_id IS NULL
            AND record_id IS NOT NULL
            AND record_id > 0
            AND occurred_at IS NOT NULL
            AND record_scan_after_id = 0
            AND record_scan_upper_id = 0
        )
        OR (
            trigger_type = 'schedule'
            AND workflow_date_enrollment_id IS NULL
            AND record_id IS NULL
            AND occurred_at IS NULL
        )
        OR (
            trigger_type = 'date_reconcile'
            AND workflow_date_enrollment_id IS NULL
            AND (record_id IS NULL OR record_id > 0)
            AND occurred_at IS NULL
        )
        OR (
            trigger_type = 'date'
            AND workflow_date_enrollment_id IS NOT NULL
            AND record_id IS NOT NULL
            AND record_id > 0
            AND occurred_at IS NULL
            AND record_scan_after_id = 0
            AND record_scan_upper_id = 0
        )
    ),
    ADD CONSTRAINT fk_workflow_trigger_outbox_date_enrollment
        FOREIGN KEY (workspace_id, workflow_date_enrollment_id)
        REFERENCES workflow_date_enrollment(workspace_id, id)
        ON DELETE RESTRICT,
    ADD INDEX idx_workflow_trigger_outbox_date_enrollment
        (workspace_id, workflow_date_enrollment_id);

ALTER TABLE workflow_run
    ADD COLUMN date_field VARCHAR(48)
        CHARACTER SET ascii COLLATE ascii_bin NULL AFTER trigger_outbox_id,
    ADD COLUMN date_source_date DATE NULL AFTER date_field,
    ADD COLUMN date_scheduled_local_date DATE NULL AFTER date_source_date,
    ADD COLUMN date_due_at DATETIME(6) NULL AFTER date_scheduled_local_date,
    DROP CHECK chk_workflow_run_trigger,
    ADD CONSTRAINT chk_workflow_run_trigger
        CHECK (trigger_type IN ('entity_change', 'schedule', 'manual', 'date')),
    ADD CONSTRAINT chk_workflow_run_date_source CHECK (
        (
            trigger_type = 'date'
            AND date_field IS NOT NULL
            AND date_field = 'expectedCloseDate'
            AND date_source_date IS NOT NULL
            AND date_scheduled_local_date IS NOT NULL
            AND date_due_at IS NOT NULL
        )
        OR (
            trigger_type <> 'date'
            AND date_field IS NULL
            AND date_source_date IS NULL
            AND date_scheduled_local_date IS NULL
            AND date_due_at IS NULL
        )
    );
