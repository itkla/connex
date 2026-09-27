-- The attachment row is the durable queue entry and survives worker restarts.
ALTER TABLE attachment
    ADD COLUMN scan_last_attempt_at DATETIME(6) NULL,
    ADD COLUMN scan_owner VARCHAR(36) NULL,
    ADD COLUMN scan_lease_until DATETIME(6) NULL,
    ADD COLUMN scan_next_attempt_at DATETIME(6) NULL,
    ADD INDEX idx_attachment_scan_due (workspace_id, scan_state, scan_next_attempt_at, id);
