CREATE TABLE saved_view (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id  INT NOT NULL,
    user_id       INT NOT NULL,
    record_type   VARCHAR(16) NOT NULL,
    name          VARCHAR(128) NOT NULL,
    config_json   JSON NOT NULL,
    position      INT NOT NULL DEFAULT 0,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_saved_view_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    CONSTRAINT fk_saved_view_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    UNIQUE KEY uq_saved_view_name (workspace_id, user_id, record_type, name),
    INDEX idx_saved_view_owner (workspace_id, user_id, record_type, position)
);
