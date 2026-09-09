-- Additive columns are deliberately ignored by older application binaries.
-- Existing objects default to unreadable until a scanner supplies a clean decision.
ALTER TABLE attachment
    ADD COLUMN scan_state VARCHAR(24) NOT NULL DEFAULT 'pending',
    ADD COLUMN scan_engine VARCHAR(128) NULL,
    ADD COLUMN scan_database_version VARCHAR(128) NULL,
    ADD COLUMN scan_signature VARCHAR(128) NULL,
    ADD COLUMN scanned_at DATETIME(6) NULL,
    ADD COLUMN scan_expires_at DATETIME(6) NULL,
    ADD COLUMN scan_attempts INT NOT NULL DEFAULT 0;
