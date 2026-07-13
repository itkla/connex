CREATE TABLE report_definition (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id  INT NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(512) NULL,
    cadence       VARCHAR(16) NOT NULL,
    template_key  VARCHAR(64) NULL,
    config_json   JSON NOT NULL,
    created_by    INT NULL,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_report_definition_cadence
        CHECK (cadence IN ('weekly', 'monthly', 'quarterly', 'custom')),
    UNIQUE KEY uq_report_definition_workspace_id (workspace_id, id),
    INDEX idx_report_definition_created_by (created_by)
) DEFAULT CHARSET=utf8mb4 COMMENT='Workspace-shared report definitions';

CREATE TABLE report_snapshot (
    id                    INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id          INT NOT NULL,
    report_definition_id  INT NOT NULL,
    period_start          DATE NOT NULL,
    period_end            DATE NOT NULL,
    computed_result       JSON NOT NULL,
    generated_by          INT NULL,
    generated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_report_snapshot_period CHECK (period_start <= period_end),
    CONSTRAINT fk_report_snapshot_definition
        FOREIGN KEY (workspace_id, report_definition_id)
        REFERENCES report_definition(workspace_id, id) ON DELETE CASCADE,
    INDEX idx_report_snapshot_definition (workspace_id, report_definition_id, generated_at),
    INDEX idx_report_snapshot_generated_by (generated_by)
) DEFAULT CHARSET=utf8mb4 COMMENT='Frozen generated report documents';
