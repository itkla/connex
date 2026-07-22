CREATE TABLE campaign_delivery (
    id                  INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id        INT NOT NULL,
    send_id             INT NOT NULL,
    person_id           INT NULL,
    address             VARCHAR(320) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'pending',
    skip_reason         VARCHAR(32) NULL,
    provider_message_id VARCHAR(255) NULL,
    attempt_count       INT NOT NULL DEFAULT 0,
    last_error          VARCHAR(512) NULL,
    unsubscribe_token   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_campaign_delivery_status CHECK (
        status IN ('pending', 'dispatching', 'dispatched', 'skipped', 'failed')),
    CONSTRAINT chk_campaign_delivery_skip_reason CHECK (
        (status = 'skipped' AND skip_reason IN (
            'consent_missing', 'suppressed', 'restricted', 'frequency_capped', 'quiet_hours', 'no_address'))
        OR (status <> 'skipped' AND skip_reason IS NULL)),
    CONSTRAINT fk_campaign_delivery_send
        FOREIGN KEY (workspace_id, send_id)
        REFERENCES campaign_send(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_campaign_delivery_person
        FOREIGN KEY (person_id) REFERENCES person(id) ON DELETE SET NULL,
    UNIQUE KEY uq_campaign_delivery_workspace_id (workspace_id, id),
    UNIQUE KEY uq_campaign_delivery_send_person (workspace_id, send_id, person_id),
    UNIQUE KEY uq_campaign_delivery_token (unsubscribe_token),
    INDEX idx_campaign_delivery_provider_message (workspace_id, provider_message_id),
    INDEX idx_campaign_delivery_send_status (workspace_id, send_id, status),
    INDEX idx_campaign_delivery_person (person_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Per-recipient materialized campaign delivery rows';

CREATE TABLE campaign_delivery_event (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id INT NOT NULL,
    delivery_id  INT NOT NULL,
    event_type   VARCHAR(16) NOT NULL,
    detail       VARCHAR(512) NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_campaign_delivery_event_type CHECK (event_type IN (
        'queued', 'dispatched', 'delivered', 'bounced', 'complained', 'unsubscribed', 'failed')),
    CONSTRAINT fk_campaign_delivery_event_delivery
        FOREIGN KEY (workspace_id, delivery_id)
        REFERENCES campaign_delivery(workspace_id, id) ON DELETE CASCADE,
    INDEX idx_campaign_delivery_event_delivery (workspace_id, delivery_id, created_at)
) DEFAULT CHARSET=utf8mb4 COMMENT='Append-only campaign delivery lifecycle events';
