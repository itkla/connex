CREATE TABLE user_object_deletion_queue (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    object_key       VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempts         INT NOT NULL DEFAULT 1,
    next_attempt_at  DATETIME(6) NOT NULL,
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_user_object_deletion_queue_attempts CHECK (attempts > 0),
    UNIQUE KEY uq_user_object_deletion_queue_key (object_key),
    INDEX idx_user_object_deletion_queue_due (next_attempt_at, id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Durable control-plane user-object deletion reconciliation';
