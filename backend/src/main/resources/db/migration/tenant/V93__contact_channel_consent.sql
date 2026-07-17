CREATE TABLE contact_channel_consent (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id INT NOT NULL,
    person_id    INT NOT NULL,
    channel      VARCHAR(16) NOT NULL,
    purpose      VARCHAR(32) NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'unknown',
    source       VARCHAR(64) NOT NULL,
    evidence_ref VARCHAR(255) NULL,
    captured_at  DATETIME NULL,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_contact_consent_channel
        CHECK (channel IN ('email', 'sms', 'line', 'whatsapp')),
    CONSTRAINT chk_contact_consent_status CHECK (status IN ('granted', 'revoked', 'unknown')),
    CONSTRAINT fk_contact_consent_person
        FOREIGN KEY (workspace_id, person_id)
        REFERENCES person(workspace_id, id) ON DELETE CASCADE,
    UNIQUE KEY uq_contact_consent_workspace_id (workspace_id, id),
    UNIQUE KEY uq_contact_consent_subject (workspace_id, person_id, channel, purpose)
) DEFAULT CHARSET=utf8mb4 COMMENT='Current per-person contact-channel consent state';

CREATE TABLE contact_channel_consent_event (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id  INT NOT NULL,
    consent_id    INT NOT NULL,
    person_id     INT NOT NULL,
    channel       VARCHAR(16) NOT NULL,
    purpose       VARCHAR(32) NOT NULL,
    status        VARCHAR(16) NOT NULL,
    source        VARCHAR(64) NOT NULL,
    evidence_ref  VARCHAR(255) NULL,
    created_by_id INT NULL,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_contact_consent_event_channel
        CHECK (channel IN ('email', 'sms', 'line', 'whatsapp')),
    CONSTRAINT chk_contact_consent_event_status CHECK (status IN ('granted', 'revoked', 'unknown')),
    CONSTRAINT fk_contact_consent_event_consent
        FOREIGN KEY (workspace_id, consent_id)
        REFERENCES contact_channel_consent(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_contact_consent_event_person
        FOREIGN KEY (workspace_id, person_id)
        REFERENCES person(workspace_id, id) ON DELETE CASCADE,
    INDEX idx_contact_consent_event_consent (workspace_id, consent_id, created_at),
    INDEX idx_contact_consent_event_created_by (created_by_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Append-only contact-channel consent history';
