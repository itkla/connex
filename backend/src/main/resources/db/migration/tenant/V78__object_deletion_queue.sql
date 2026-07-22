CREATE TABLE object_deletion_queue (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    workspace_id     INT NOT NULL,
    object_key       VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempts         INT NOT NULL DEFAULT 1,
    next_attempt_at  DATETIME(6) NOT NULL,
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT chk_object_deletion_queue_attempts CHECK (attempts > 0),
    UNIQUE KEY uq_object_deletion_queue_workspace_key (workspace_id, object_key),
    INDEX idx_object_deletion_queue_due (workspace_id, next_attempt_at, id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Durable tenant-object deletion reconciliation';
