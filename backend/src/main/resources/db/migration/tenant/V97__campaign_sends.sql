CREATE TABLE campaign_send (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id      INT NOT NULL,
    campaign_id       INT NOT NULL,
    snapshot_id       INT NOT NULL,
    message_id        INT NOT NULL,
    message_version   INT NOT NULL,
    channel           VARCHAR(16) NOT NULL DEFAULT 'email',
    purpose           VARCHAR(32) NOT NULL DEFAULT 'marketing',
    provider_id       VARCHAR(64) NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'draft',
    scheduled_at      DATETIME NULL,
    started_at        DATETIME NULL,
    completed_at      DATETIME NULL,
    total_recipients  INT NOT NULL DEFAULT 0,
    dispatched_count  INT NOT NULL DEFAULT 0,
    skipped_count     INT NOT NULL DEFAULT 0,
    failed_count      INT NOT NULL DEFAULT 0,
    created_by_id     INT NULL,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_campaign_send_channel
        CHECK (channel IN ('email', 'sms', 'line', 'whatsapp')),
    CONSTRAINT chk_campaign_send_status CHECK (
        status IN ('draft', 'queued', 'running', 'paused', 'completed', 'failed', 'cancelled')),
    CONSTRAINT chk_campaign_send_message_version CHECK (message_version > 0),
    CONSTRAINT chk_campaign_send_counts CHECK (
        total_recipients >= 0 AND dispatched_count >= 0
        AND skipped_count >= 0 AND failed_count >= 0),
    CONSTRAINT fk_campaign_send_campaign
        FOREIGN KEY (workspace_id, campaign_id)
        REFERENCES campaign(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_campaign_send_snapshot
        FOREIGN KEY (workspace_id, snapshot_id)
        REFERENCES campaign_audience_snapshot(workspace_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_campaign_send_message
        FOREIGN KEY (workspace_id, message_id)
        REFERENCES campaign_message(workspace_id, id) ON DELETE RESTRICT,
    UNIQUE KEY uq_campaign_send_workspace_id (workspace_id, id),
    INDEX idx_campaign_send_campaign (workspace_id, campaign_id),
    INDEX idx_campaign_send_status (workspace_id, status),
    INDEX idx_campaign_send_created_by (created_by_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='A campaign send bound to a frozen audience snapshot and message version';
