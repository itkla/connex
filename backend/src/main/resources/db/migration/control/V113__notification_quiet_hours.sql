CREATE TABLE notification_quiet_hours (
    user_id      INT PRIMARY KEY,
    enabled      BOOLEAN NOT NULL DEFAULT FALSE,
    timezone     VARCHAR(64) NOT NULL,
    start_local  TIME NOT NULL,
    end_local    TIME NOT NULL,
    days_mask    TINYINT UNSIGNED NOT NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_notification_quiet_hours_user
        FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT chk_notification_quiet_days CHECK (days_mask BETWEEN 1 AND 127),
    CONSTRAINT chk_notification_quiet_window CHECK (start_local <> end_local)
);
