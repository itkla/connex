CREATE TABLE report_schedule (
    id                    INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id          INT NOT NULL,
    report_definition_id  INT NOT NULL,
    cadence               VARCHAR(16) NOT NULL,
    recipient_user_ids    JSON NOT NULL,
    timezone              VARCHAR(64) NOT NULL,
    hour_of_day           TINYINT UNSIGNED NOT NULL,
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    run_as_user_id        INT NOT NULL,
    next_run_at           DATETIME NOT NULL,
    last_run_at           DATETIME NULL,
    created_by            INT NOT NULL,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_report_schedule_cadence
        CHECK (cadence IN ('weekly', 'monthly', 'quarterly')),
    CONSTRAINT chk_report_schedule_hour CHECK (hour_of_day BETWEEN 0 AND 23),
    CONSTRAINT fk_report_schedule_definition
        FOREIGN KEY (workspace_id, report_definition_id)
        REFERENCES report_definition(workspace_id, id) ON DELETE CASCADE,
    UNIQUE KEY uq_report_schedule_workspace_id (workspace_id, id),
    UNIQUE KEY uq_report_schedule_workspace_report (workspace_id, report_definition_id),
    INDEX idx_report_schedule_due (enabled, next_run_at)
) DEFAULT CHARSET=utf8mb4 COMMENT='Scheduled delivery of frozen reports';
