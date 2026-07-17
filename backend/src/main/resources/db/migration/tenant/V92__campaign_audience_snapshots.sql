CREATE TABLE campaign_audience_snapshot (
    id                    INT AUTO_INCREMENT PRIMARY KEY,
    campaign_id           INT NOT NULL,
    workspace_id          INT NOT NULL,
    version               INT NOT NULL,
    record_type           VARCHAR(16) NOT NULL,
    definition_json       JSON NOT NULL,
    estimated_included    INT NOT NULL,
    excluded_total        INT NOT NULL,
    excluded_consent      INT NOT NULL,
    excluded_suppressed   INT NOT NULL,
    excluded_restricted   INT NOT NULL,
    created_by_id         INT NULL,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_campaign_snapshot_version CHECK (version > 0),
    CONSTRAINT chk_campaign_snapshot_record_type
        CHECK (record_type IN ('person', 'company', 'deal')),
    CONSTRAINT chk_campaign_snapshot_counts CHECK (
        estimated_included >= 0
        AND excluded_consent >= 0
        AND excluded_suppressed >= 0
        AND excluded_restricted >= 0
        AND excluded_total = excluded_consent + excluded_suppressed + excluded_restricted
    ),
    CONSTRAINT fk_campaign_snapshot_campaign
        FOREIGN KEY (workspace_id, campaign_id)
        REFERENCES campaign(workspace_id, id) ON DELETE RESTRICT,
    UNIQUE KEY uq_campaign_snapshot_workspace_id (workspace_id, id),
    UNIQUE KEY uq_campaign_snapshot_version (campaign_id, version),
    INDEX idx_campaign_snapshot_created_by (created_by_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Immutable campaign audience snapshots';

CREATE TABLE campaign_audience_member (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    snapshot_id       INT NOT NULL,
    workspace_id      INT NOT NULL,
    record_type       VARCHAR(16) NOT NULL,
    record_id         INT NOT NULL,
    status            VARCHAR(16) NOT NULL,
    exclusion_reason  VARCHAR(32) NULL,
    CONSTRAINT chk_campaign_member_record_type
        CHECK (record_type IN ('person', 'company', 'deal')),
    CONSTRAINT chk_campaign_member_status CHECK (status IN ('included', 'excluded')),
    CONSTRAINT chk_campaign_member_reason CHECK (
        (status = 'included' AND exclusion_reason IS NULL)
        OR (status = 'excluded' AND exclusion_reason IN ('consent_missing', 'suppressed', 'restricted'))
    ),
    CONSTRAINT fk_campaign_member_snapshot
        FOREIGN KEY (workspace_id, snapshot_id)
        REFERENCES campaign_audience_snapshot(workspace_id, id) ON DELETE RESTRICT,
    UNIQUE KEY uq_campaign_member_snapshot_record (snapshot_id, record_id),
    INDEX idx_campaign_member_snapshot_status (snapshot_id, status)
) DEFAULT CHARSET=utf8mb4 COMMENT='Immutable classified members of a campaign audience snapshot';
