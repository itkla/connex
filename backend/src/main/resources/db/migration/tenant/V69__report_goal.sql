CREATE TABLE report_goal (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id  INT NOT NULL,
    owner_id      INT NULL,
    owner_scope_id INT GENERATED ALWAYS AS (COALESCE(owner_id, 0)) STORED,
    metric        VARCHAR(32) NOT NULL,
    period_type   VARCHAR(16) NOT NULL,
    period_start  DATE NOT NULL,
    target_value  DECIMAL(15,2) NOT NULL,
    currency      VARCHAR(8) NOT NULL,
    created_by    INT NULL,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_report_goal_target_value CHECK (target_value >= 0),
    UNIQUE KEY uq_report_goal_workspace_id (workspace_id, id),
    UNIQUE KEY uq_report_goal_scope_period
        (workspace_id, owner_id, metric, period_type, period_start, currency),
    UNIQUE KEY uq_report_goal_effective_scope_period
        (workspace_id, owner_scope_id, metric, period_type, period_start, currency),
    INDEX idx_report_goal_workspace_owner (workspace_id, owner_id),
    INDEX idx_report_goal_workspace_period (workspace_id, period_type, period_start)
) DEFAULT CHARSET=utf8mb4 COMMENT='Workspace and owner revenue targets for report attainment';
