-- report_schedule_id intentionally uses a single-column foreign key because MySQL rejects
-- ON DELETE SET NULL for a composite key whose workspace_id leg is NOT NULL. ReportService locks
-- the workspace-scoped schedule row before scheduled snapshot insertion to enforce tenant equality.
ALTER TABLE report_snapshot
    ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'manual' AFTER computed_result,
    ADD COLUMN report_schedule_id INT NULL AFTER origin,
    ADD CONSTRAINT chk_report_snapshot_origin CHECK (origin IN ('manual', 'scheduled')),
    ADD INDEX idx_report_snapshot_schedule (report_schedule_id, workspace_id, origin, generated_at, id),
    ADD CONSTRAINT fk_report_snapshot_schedule FOREIGN KEY (report_schedule_id) REFERENCES report_schedule(id) ON DELETE SET NULL;
