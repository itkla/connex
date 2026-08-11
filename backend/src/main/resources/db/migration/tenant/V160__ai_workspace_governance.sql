CREATE TABLE ai_workspace_governance (
    workspace_id         INT NOT NULL PRIMARY KEY,
    ai_enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    assistant_max_steps  TINYINT UNSIGNED NOT NULL DEFAULT 6,
    created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                             ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_ai_workspace_governance_max_steps
        CHECK (assistant_max_steps BETWEEN 1 AND 12)
) DEFAULT CHARSET=utf8mb4;
