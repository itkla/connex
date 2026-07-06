-- Records when each deal reached each stage, one row per achievement (append-only).
-- Powers the per-stage timestamps in the deal lifecycle progress view.
CREATE TABLE deal_stage_history (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id INT NOT NULL COMMENT 'Workspace ID',
    deal_id      INT NOT NULL COMMENT 'Deal that reached the stage',
    stage_id     INT NULL COMMENT 'Stage that was reached; NULL once that stage is deleted',
    stage_name   VARCHAR(255) NULL COMMENT 'Stage name snapshot, so history survives a stage rename or deletion',
    achieved_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'When the deal reached the stage',
    CONSTRAINT fk_deal_stage_history_deal FOREIGN KEY (workspace_id, deal_id) REFERENCES deal(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_deal_stage_history_stage FOREIGN KEY (stage_id) REFERENCES stage(id) ON DELETE SET NULL,
    INDEX idx_deal_stage_history_deal (workspace_id, deal_id, achieved_at)
) DEFAULT CHARSET=utf8mb4 COMMENT='Records when each deal reached each stage';

-- Seed each existing deal's current stage so the timeline is not empty for pre-existing deals.
-- updated_at is the closest available proxy for when a deal reached its present stage; going
-- forward every transition is stamped accurately at the moment it happens.
INSERT INTO deal_stage_history (workspace_id, deal_id, stage_id, stage_name, achieved_at)
SELECT d.workspace_id, d.id, d.stage_id, s.name, d.updated_at
FROM deal d
JOIN stage s ON s.id = d.stage_id;
