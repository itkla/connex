-- ============================================================================
-- delivery_provider_config : a workspace's per-channel outbound delivery provider
-- selection and settings. When an enabled row exists for a channel it overrides
-- the built-in SMTP transport; otherwise delivery falls back to the workspace
-- mail config unchanged. The send credential (ESP API key) and the inbound
-- webhook signing secret live ENCRYPTED in the central secret store; this table
-- holds only opaque references and masked metadata. Owner/admin managed
-- (WORKSPACE_SETTINGS). One row per (workspace, channel).
-- ============================================================================

CREATE TABLE delivery_provider_config (
    id                  INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id        INT NOT NULL,
    channel             VARCHAR(16) NOT NULL,
    provider            VARCHAR(32) NOT NULL,
    endpoint            VARCHAR(2048) NULL,
    from_address        VARCHAR(320) NULL,
    from_name           VARCHAR(255) NULL,
    credential_ref      VARCHAR(255) NULL,
    credential_last4    VARCHAR(8) NULL,
    webhook_token_hash  CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    webhook_secret_ref  VARCHAR(255) NULL,
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    created_by_id       INT NULL,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_delivery_provider_config_channel
        CHECK (channel IN ('email', 'sms', 'line', 'whatsapp')),
    CONSTRAINT chk_delivery_provider_config_provider
        CHECK (provider IN ('smtp', 'http_esp')),
    UNIQUE KEY uq_delivery_provider_config_workspace_channel (workspace_id, channel),
    UNIQUE KEY uq_delivery_provider_config_workspace_id (workspace_id, id),
    UNIQUE KEY uq_delivery_provider_config_webhook_token (webhook_token_hash),
    INDEX idx_delivery_provider_config_created_by (created_by_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Per-workspace, per-channel outbound delivery provider settings';

-- Widen the delivery status vocabulary with the provider-reported terminal states and record which
-- provider a delivery was dispatched through so an inbound event can be attributed to its adapter.
ALTER TABLE campaign_delivery
    DROP CONSTRAINT chk_campaign_delivery_status,
    ADD CONSTRAINT chk_campaign_delivery_status CHECK (
        status IN ('pending', 'dispatching', 'dispatched', 'skipped', 'failed',
                   'delivered', 'bounced', 'complained')),
    ADD COLUMN provider_id VARCHAR(32) NULL AFTER provider_message_id;

-- Attribute lifecycle events to a provider and its own event id, and make webhook replay idempotent:
-- a provider that redelivers the same event id can insert the row at most once. Internal events leave
-- both columns NULL, and MySQL permits multiple NULLs in a UNIQUE index, so they are unaffected.
ALTER TABLE campaign_delivery_event
    ADD COLUMN provider_id VARCHAR(32) NULL AFTER detail,
    ADD COLUMN provider_event_id VARCHAR(255) NULL AFTER provider_id,
    ADD CONSTRAINT uq_campaign_delivery_event_provider
        UNIQUE (workspace_id, provider_id, provider_event_id);
