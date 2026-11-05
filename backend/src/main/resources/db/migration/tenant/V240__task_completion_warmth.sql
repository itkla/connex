-- Existing completions remain undated: updated_at is not evidence of completion.
ALTER TABLE task
    ADD COLUMN completed_at DATETIME NULL,
    ALGORITHM=INSTANT;

CREATE TABLE warmth_notification_model_state (
    workspace_id INT NOT NULL PRIMARY KEY,
    model_version VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Only existing workspaces need a quiet model cutover; new workspaces start on the live model.
INSERT INTO warmth_notification_model_state (workspace_id, model_version)
SELECT workspace_id, 'warmth-v1' FROM person
UNION SELECT workspace_id, 'warmth-v1' FROM company
UNION SELECT workspace_id, 'warmth-v1' FROM person_share
UNION SELECT workspace_id, 'warmth-v1' FROM company_share
UNION SELECT workspace_id, 'warmth-v1' FROM deal
UNION SELECT workspace_id, 'warmth-v1' FROM task
UNION SELECT workspace_id, 'warmth-v1' FROM activity
UNION SELECT workspace_id, 'warmth-v1' FROM note
UNION SELECT workspace_id, 'warmth-v1' FROM relationship_signal
UNION SELECT workspace_id, 'warmth-v1' FROM notification
UNION SELECT workspace_id, 'warmth-v1' FROM historical_notification_baseline;

ALTER TABLE historical_notification_baseline
    ADD COLUMN model_version VARCHAR(32) NOT NULL DEFAULT 'warmth-v1',
    ALGORITHM=INSTANT;

ALTER TABLE relationship_signal
    ADD COLUMN source_model_version VARCHAR(32) NOT NULL DEFAULT 'warmth-v1',
    ALGORITHM=INSTANT;
