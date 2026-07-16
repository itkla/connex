CREATE TABLE campaign (
    id                  INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id        INT NOT NULL,
    name                VARCHAR(128) NOT NULL,
    objective           VARCHAR(255) NULL,
    type                VARCHAR(32) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'draft',
    owner_user_id       INT NULL,
    budget_amount       DECIMAL(15,2) NULL,
    budget_currency     CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NULL,
    start_at            DATETIME NULL,
    end_at              DATETIME NULL,
    parent_campaign_id  INT NULL,
    created_by_id       INT NULL,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_campaign_status
        CHECK (status IN ('draft', 'scheduled', 'active', 'paused', 'completed', 'archived')),
    CONSTRAINT chk_campaign_budget_amount CHECK (budget_amount IS NULL OR budget_amount >= 0),
    CONSTRAINT chk_campaign_budget_pair CHECK (
        (budget_amount IS NULL AND budget_currency IS NULL)
        OR (budget_amount IS NOT NULL AND budget_currency IS NOT NULL)
    ),
    CONSTRAINT chk_campaign_budget_currency
        CHECK (budget_currency IS NULL OR budget_currency REGEXP '^[A-Z]{3}$'),
    CONSTRAINT chk_campaign_dates CHECK (start_at IS NULL OR end_at IS NULL OR start_at <= end_at),
    UNIQUE KEY uq_campaign_workspace_id (workspace_id, id),
    CONSTRAINT fk_campaign_parent
        FOREIGN KEY (workspace_id, parent_campaign_id)
        REFERENCES campaign(workspace_id, id) ON DELETE RESTRICT,
    INDEX idx_campaign_workspace_status (workspace_id, status),
    INDEX idx_campaign_owner_user (owner_user_id),
    INDEX idx_campaign_created_by (created_by_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Workspace-scoped marketing campaigns';

CREATE TABLE campaign_audience (
    id               INT AUTO_INCREMENT PRIMARY KEY,
    campaign_id      INT NOT NULL,
    workspace_id     INT NOT NULL,
    record_type      VARCHAR(16) NOT NULL,
    definition_json  JSON NOT NULL,
    mode             VARCHAR(16) NOT NULL DEFAULT 'live',
    updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_campaign_audience_record_type
        CHECK (record_type IN ('person', 'company', 'deal')),
    CONSTRAINT chk_campaign_audience_mode CHECK (mode IN ('live', 'snapshot')),
    CONSTRAINT fk_campaign_audience_campaign
        FOREIGN KEY (workspace_id, campaign_id)
        REFERENCES campaign(workspace_id, id) ON DELETE CASCADE,
    UNIQUE KEY uq_campaign_audience_campaign (workspace_id, campaign_id),
    UNIQUE KEY uq_campaign_audience_workspace_id (workspace_id, id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Active smart-segment audience definition per campaign';
