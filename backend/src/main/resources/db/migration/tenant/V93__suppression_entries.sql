CREATE TABLE suppression_entry (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id  INT NOT NULL,
    scope         VARCHAR(16) NOT NULL,
    channel       VARCHAR(16) NOT NULL,
    address       VARCHAR(320) NOT NULL,
    person_id     INT NULL,
    reason        VARCHAR(32) NOT NULL,
    note          VARCHAR(512) NULL,
    created_by_id INT NULL,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_suppression_scope CHECK (scope IN ('workspace', 'global')),
    CONSTRAINT chk_suppression_channel
        CHECK (channel IN ('email', 'sms', 'line', 'whatsapp')),
    CONSTRAINT chk_suppression_reason
        CHECK (reason IN ('unsubscribe', 'hard_bounce', 'complaint', 'do_not_contact', 'manual')),
    CONSTRAINT fk_suppression_person
        FOREIGN KEY (person_id) REFERENCES person(id) ON DELETE SET NULL,
    INDEX idx_suppression_workspace_channel_address (workspace_id, channel, address),
    INDEX idx_suppression_person (person_id),
    INDEX idx_suppression_created_by (created_by_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Workspace-owned contact-channel suppression entries';
