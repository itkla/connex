CREATE TABLE job_run (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    job_name     VARCHAR(64) NOT NULL,
    workspace_id INT NULL,
    status       VARCHAR(16) NOT NULL,
    started_at   DATETIME NOT NULL,
    finished_at  DATETIME NOT NULL,
    detail       JSON NULL,
    CONSTRAINT chk_job_run_status CHECK (status IN ('succeeded', 'failed', 'skipped')),
    INDEX idx_job_run_job_workspace_started (job_name, workspace_id, started_at),
    INDEX idx_job_run_workspace_started (workspace_id, started_at)
) DEFAULT CHARSET=utf8mb4;
