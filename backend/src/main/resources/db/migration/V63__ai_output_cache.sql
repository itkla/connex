CREATE TABLE ai_output_cache (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id  INT NOT NULL,
    feature       VARCHAR(64) NOT NULL,
    subject_a_id  INT NOT NULL,
    subject_b_id  INT NOT NULL DEFAULT 0,
    content_hash  CHAR(64) NOT NULL,
    payload       JSON NOT NULL,
    warnings      INT NOT NULL DEFAULT 0,
    generated_at  VARCHAR(40) NOT NULL,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_output_cache_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    UNIQUE KEY uq_ai_output_cache_subject (workspace_id, feature, subject_a_id, subject_b_id)
);
