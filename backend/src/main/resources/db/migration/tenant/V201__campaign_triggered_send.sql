ALTER TABLE campaign_audience_snapshot
    ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'audience' AFTER purpose,
    ADD COLUMN triggered_message_id INT NULL AFTER origin,
    ADD COLUMN triggered_message_version INT NULL AFTER triggered_message_id,
    ADD CONSTRAINT chk_campaign_snapshot_origin CHECK (
        (origin = 'audience'
            AND triggered_message_id IS NULL
            AND triggered_message_version IS NULL)
        OR (origin = 'triggered'
            AND triggered_message_id IS NOT NULL
            AND triggered_message_id > 0
            AND triggered_message_version IS NOT NULL
            AND triggered_message_version > 0)),
    ADD CONSTRAINT fk_campaign_snapshot_triggered_message
        FOREIGN KEY (workspace_id, triggered_message_id)
        REFERENCES campaign_message(workspace_id, id) ON DELETE RESTRICT,
    ADD UNIQUE KEY uq_campaign_snapshot_triggered_revision
        (workspace_id, triggered_message_id, triggered_message_version);

ALTER TABLE campaign_send
    DROP CHECK chk_campaign_send_status,
    ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'audience' AFTER snapshot_id,
    ADD COLUMN triggered_message_version INT
        GENERATED ALWAYS AS (IF(origin = 'triggered', message_version, NULL)) STORED
        AFTER message_version,
    ADD CONSTRAINT chk_campaign_send_status CHECK (
        status IN ('draft', 'queued', 'running', 'paused', 'completed', 'failed', 'cancelled',
                   'triggered')),
    ADD CONSTRAINT chk_campaign_send_origin CHECK (
        (origin = 'audience' AND status <> 'triggered')
        OR (origin = 'triggered' AND status = 'triggered')),
    ADD UNIQUE KEY uq_campaign_send_triggered
        (workspace_id, message_id, triggered_message_version);

ALTER TABLE workflow_step_run
    ADD COLUMN action_outcome VARCHAR(32) NULL AFTER next_node_id,
    ADD COLUMN action_reference_id BIGINT NULL AFTER action_outcome,
    ADD CONSTRAINT chk_workflow_step_action_outcome CHECK (
        action_outcome IS NULL
        OR action_outcome IN ('delivery_queued', 'delivery_dedup_skipped', 'delivery_capped')),
    ADD CONSTRAINT chk_workflow_step_action_reference CHECK (
        (action_outcome IS NULL AND action_reference_id IS NULL)
        OR (action_outcome IS NOT NULL AND action_reference_id IS NOT NULL
            AND action_reference_id > 0)),
    ADD INDEX idx_workflow_step_action_reference
        (workspace_id, action_reference_id);

ALTER TABLE workflow_trigger_outbox
    ADD COLUMN schedule_match_count INT UNSIGNED NOT NULL DEFAULT 0
        AFTER record_scan_upper_id,
    ADD CONSTRAINT chk_workflow_trigger_outbox_match_count
        CHECK (schedule_match_count <= 500);
