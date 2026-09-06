ALTER TABLE delivery_provider_config
    ADD COLUMN config_generation BIGINT NOT NULL DEFAULT 1 AFTER enabled,
    ADD COLUMN idempotent_submission BOOLEAN NOT NULL DEFAULT FALSE AFTER config_generation,
    ADD CONSTRAINT chk_delivery_provider_config_generation CHECK (config_generation > 0);

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

ALTER TABLE campaign_delivery
    DROP INDEX uq_campaign_delivery_send_person,
    ADD COLUMN attempt_target_fingerprint CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL AFTER provider_id,
    ADD COLUMN last_error_code VARCHAR(32) NULL AFTER last_error,
    ADD COLUMN dispatch_lease_owner VARCHAR(36) NULL AFTER last_error_code,
    ADD COLUMN dispatch_lease_until DATETIME(6) NULL AFTER dispatch_lease_owner,
    ADD COLUMN reconciliation_required_at DATETIME(6) NULL AFTER dispatch_lease_until,
    ADD COLUMN reconciliation_outcome VARCHAR(24) NULL AFTER reconciliation_required_at,
    ADD COLUMN dedupe_active TINYINT
        GENERATED ALWAYS AS (
            IF(reconciliation_outcome = 'operator_not_delivered', NULL, 1)) STORED
        AFTER reconciliation_outcome,
    ADD CONSTRAINT chk_campaign_delivery_dispatch_lease CHECK (
        (dispatch_lease_owner IS NULL AND dispatch_lease_until IS NULL)
        OR (dispatch_lease_owner IS NOT NULL AND dispatch_lease_until IS NOT NULL)),
    ADD CONSTRAINT chk_campaign_delivery_last_error_code CHECK (
        last_error_code IS NULL
        OR last_error_code IN ('provider_timeout', 'provider_rejected', 'deadline_ambiguous',
                               'delivery_target_changed', 'relay_error')),
    ADD CONSTRAINT chk_campaign_delivery_reconciliation CHECK (
        (reconciliation_required_at IS NULL
            OR (status = 'failed' AND reconciliation_outcome IS NULL))
        AND (reconciliation_outcome IS NULL
            OR (reconciliation_required_at IS NULL
                AND ((reconciliation_outcome = 'operator_delivered'
                        AND status IN ('dispatched', 'delivered', 'bounced', 'complained', 'failed'))
                    OR (reconciliation_outcome = 'operator_not_delivered'
                        AND status = 'failed'))))),
    ADD UNIQUE KEY uq_campaign_delivery_send_person
        (workspace_id, send_id, person_id, dedupe_active),
    ADD INDEX idx_campaign_delivery_dispatch_lease
        (workspace_id, status, dispatch_lease_until);

ALTER TABLE workflow_step_run
    ADD COLUMN action_outcome VARCHAR(32) NULL AFTER next_node_id,
    ADD COLUMN action_reference_id BIGINT NULL AFTER action_outcome,
    ADD CONSTRAINT chk_workflow_step_action_outcome CHECK (
        action_outcome IS NULL
        OR action_outcome IN ('delivery_queued', 'delivery_dedup_skipped', 'delivery_capped',
                              'delivery_reconciliation_required')),
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
