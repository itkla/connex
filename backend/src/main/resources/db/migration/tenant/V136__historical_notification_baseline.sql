CREATE TABLE historical_notification_baseline (
    workspace_id INT NOT NULL,
    recipient_id INT NOT NULL,
    dedupe_key VARCHAR(255) NOT NULL,
    notification_type VARCHAR(64) NOT NULL,
    baseline_severity VARCHAR(16) NOT NULL,
    source_state_hash BINARY(32) NOT NULL,
    import_run_id BINARY(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_historical_notification_baseline (
        workspace_id,
        recipient_id,
        dedupe_key
    ),
    KEY idx_historical_notification_baseline_recipient (recipient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
