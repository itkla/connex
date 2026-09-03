ALTER TABLE campaign_audience
    ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'email' AFTER mode,
    ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'marketing' AFTER channel,
    ADD CONSTRAINT chk_campaign_audience_channel
        CHECK (channel IN ('email', 'sms', 'line', 'whatsapp'));

ALTER TABLE campaign_audience_snapshot
    ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'email' AFTER definition_json,
    ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'marketing' AFTER channel,
    ADD COLUMN excluded_no_address INT NOT NULL DEFAULT 0 AFTER excluded_restricted,
    ADD CONSTRAINT chk_campaign_snapshot_channel
        CHECK (channel IN ('email', 'sms', 'line', 'whatsapp')),
    DROP CONSTRAINT chk_campaign_snapshot_counts,
    ADD CONSTRAINT chk_campaign_snapshot_counts CHECK (
        estimated_included >= 0
        AND excluded_consent >= 0
        AND excluded_suppressed >= 0
        AND excluded_restricted >= 0
        AND excluded_no_address >= 0
        AND excluded_total = excluded_consent + excluded_suppressed
            + excluded_restricted + excluded_no_address
    );

ALTER TABLE campaign_audience_member
    DROP CONSTRAINT chk_campaign_member_reason,
    ADD CONSTRAINT chk_campaign_member_reason CHECK (
        (status = 'included' AND exclusion_reason IS NULL)
        OR (status = 'excluded' AND exclusion_reason IN (
            'consent_missing', 'consent_revoked', 'suppressed', 'restricted', 'no_address')));

ALTER TABLE campaign_audience_export
    MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'draft',
    MODIFY COLUMN pushed_count INT NULL DEFAULT 0,
    MODIFY COLUMN failed_count INT NULL DEFAULT 0,
    ADD COLUMN frozen_member_ids_json JSON NULL AFTER external_list_id,
    ADD COLUMN pushed_member_ids_json JSON NULL AFTER frozen_member_ids_json,
    ADD COLUMN attempt INT NOT NULL DEFAULT 1 AFTER status,
    ADD COLUMN lease_until DATETIME(6) NULL AFTER attempt,
    DROP CONSTRAINT chk_campaign_audience_export_status,
    ADD CONSTRAINT chk_campaign_audience_export_status CHECK (
        status IN ('draft', 'running', 'completed', 'failed', 'needs_reconciliation')),
    ADD CONSTRAINT chk_campaign_audience_export_frozen_members
        CHECK (frozen_member_ids_json IS NULL OR JSON_TYPE(frozen_member_ids_json) = 'ARRAY'),
    ADD CONSTRAINT chk_campaign_audience_export_pushed_members
        CHECK (pushed_member_ids_json IS NULL OR JSON_TYPE(pushed_member_ids_json) = 'ARRAY'),
    DROP CONSTRAINT chk_campaign_audience_export_counts,
    ADD CONSTRAINT chk_campaign_audience_export_counts CHECK (
        total_members >= 0
        AND (
            (pushed_count IS NULL AND failed_count IS NULL)
            OR (pushed_count IS NOT NULL AND failed_count IS NOT NULL
                AND pushed_count >= 0 AND failed_count >= 0)
        )),
    ADD CONSTRAINT chk_campaign_audience_export_attempt CHECK (attempt > 0);

UPDATE campaign_audience_export
SET status = 'needs_reconciliation',
    pushed_count = NULL,
    failed_count = NULL
WHERE status = 'running'
   OR (status = 'failed' AND external_list_id IS NOT NULL);

ALTER TABLE campaign_audience_export
    ADD CONSTRAINT chk_campaign_audience_export_lease CHECK (
        status = 'running'
        OR (status <> 'running' AND lease_until IS NULL));

ALTER TABLE connector_config
    ADD COLUMN config_version BIGINT NOT NULL DEFAULT 1 AFTER enabled,
    ADD CONSTRAINT chk_connector_config_version CHECK (config_version > 0);
