CREATE TABLE campaign_message (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id  INT NOT NULL,
    campaign_id   INT NOT NULL,
    channel       VARCHAR(16) NOT NULL DEFAULT 'email',
    name          VARCHAR(128) NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'draft',
    created_by_id INT NULL,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_campaign_message_channel
        CHECK (channel IN ('email', 'sms', 'line', 'whatsapp')),
    CONSTRAINT chk_campaign_message_status CHECK (status IN ('draft', 'final')),
    CONSTRAINT fk_campaign_message_campaign
        FOREIGN KEY (workspace_id, campaign_id)
        REFERENCES campaign(workspace_id, id) ON DELETE CASCADE,
    UNIQUE KEY uq_campaign_message_workspace_id (workspace_id, id),
    INDEX idx_campaign_message_campaign (workspace_id, campaign_id),
    INDEX idx_campaign_message_created_by (created_by_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Workspace-scoped campaign message definitions';

CREATE TABLE campaign_message_revision (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id INT NOT NULL,
    message_id   INT NOT NULL,
    version      INT NOT NULL,
    locale       VARCHAR(8) NOT NULL DEFAULT 'en',
    subject      VARCHAR(255) NOT NULL,
    body_html    MEDIUMTEXT NOT NULL,
    body_text    MEDIUMTEXT NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_campaign_message_revision_version CHECK (version > 0),
    CONSTRAINT chk_campaign_message_revision_locale CHECK (locale IN ('en', 'ja')),
    CONSTRAINT fk_campaign_message_revision_message
        FOREIGN KEY (workspace_id, message_id)
        REFERENCES campaign_message(workspace_id, id) ON DELETE CASCADE,
    UNIQUE KEY uq_campaign_message_revision_workspace_id (workspace_id, id),
    UNIQUE KEY uq_campaign_message_revision_version (workspace_id, message_id, version, locale)
) DEFAULT CHARSET=utf8mb4 COMMENT='Immutable versioned campaign message content per locale';
