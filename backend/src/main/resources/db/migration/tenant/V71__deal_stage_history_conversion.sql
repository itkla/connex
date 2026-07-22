ALTER TABLE deal_stage_history
    ADD COLUMN conversion_eligible BOOLEAN NOT NULL DEFAULT FALSE AFTER achieved_at,
    ADD INDEX idx_deal_stage_history_conversion
        (workspace_id, conversion_eligible, stage_id, deal_id);
