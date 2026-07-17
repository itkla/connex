-- ============================================================================
-- campaign_audience_export : a record of one push of a frozen audience snapshot's
-- eligible included members to a third-party marketing connector. Bound to an
-- immutable snapshot (ON DELETE RESTRICT so the pushed audience is never severed
-- from its record) and never mutating the snapshot itself. Counts are the eligible
-- total after a fresh eligibility re-check, and the connector-reported pushed and
-- failed tallies. Owner/admin managed (CAMPAIGN_MANAGE to create, CAMPAIGN_VIEW to
-- read).
-- ============================================================================

CREATE TABLE campaign_audience_export (
    id                INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id      INT NOT NULL,
    campaign_id       INT NOT NULL,
    snapshot_id       INT NOT NULL,
    connector         VARCHAR(32) NOT NULL,
    external_list_id  VARCHAR(255) NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'draft',
    total_members     INT NOT NULL DEFAULT 0,
    pushed_count      INT NOT NULL DEFAULT 0,
    failed_count      INT NOT NULL DEFAULT 0,
    created_by_id     INT NULL,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_campaign_audience_export_connector
        CHECK (connector IN ('http_list')),
    CONSTRAINT chk_campaign_audience_export_status CHECK (
        status IN ('draft', 'running', 'completed', 'failed')),
    CONSTRAINT chk_campaign_audience_export_counts CHECK (
        total_members >= 0 AND pushed_count >= 0 AND failed_count >= 0),
    CONSTRAINT fk_campaign_audience_export_campaign
        FOREIGN KEY (workspace_id, campaign_id)
        REFERENCES campaign(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_campaign_audience_export_snapshot
        FOREIGN KEY (workspace_id, snapshot_id)
        REFERENCES campaign_audience_snapshot(workspace_id, id) ON DELETE RESTRICT,
    UNIQUE KEY uq_campaign_audience_export_workspace_id (workspace_id, id),
    INDEX idx_campaign_audience_export_campaign (workspace_id, campaign_id),
    INDEX idx_campaign_audience_export_status (workspace_id, status),
    INDEX idx_campaign_audience_export_created_by (created_by_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='A push of a frozen audience snapshot to a third-party marketing connector';
