CREATE TABLE notification_recipient_state (
    recipient_id   INT PRIMARY KEY,
    state_version  BIGINT NOT NULL DEFAULT 0,
    updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_notification_state_recipient
        FOREIGN KEY (recipient_id) REFERENCES app_user(id) ON DELETE CASCADE
) DEFAULT CHARSET=utf8mb4 COMMENT='Monotonic notification state version per recipient';

INSERT INTO notification_recipient_state (recipient_id, state_version)
SELECT id, 0 FROM app_user;
