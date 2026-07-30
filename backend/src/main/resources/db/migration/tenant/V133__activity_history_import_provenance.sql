ALTER TABLE activity
    ADD COLUMN history_import_key BINARY(32) NULL,
    ADD COLUMN history_payload_hash BINARY(32) NULL,
    ADD COLUMN history_source_system VARCHAR(64) NULL,
    ADD COLUMN history_source_id VARCHAR(512) NULL,
    ADD COLUMN history_source_row_ref VARCHAR(64) NULL,
    ADD COLUMN history_imported_at DATETIME(6) NULL,
    ADD UNIQUE KEY uq_activity_history_import (workspace_id, history_import_key),
    ADD CONSTRAINT chk_activity_history_provenance CHECK (
        (
            history_import_key IS NULL
            AND history_payload_hash IS NULL
            AND history_source_system IS NULL
            AND history_source_id IS NULL
            AND history_source_row_ref IS NULL
            AND history_imported_at IS NULL
        )
        OR (
            history_import_key IS NOT NULL
            AND history_payload_hash IS NOT NULL
            AND history_source_system IS NOT NULL
            AND history_source_row_ref IS NOT NULL
            AND history_imported_at IS NOT NULL
        )
    );
