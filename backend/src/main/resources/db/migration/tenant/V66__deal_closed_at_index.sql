ALTER TABLE deal
    ADD INDEX idx_deal_workspace_closed_at (workspace_id, closed_at);
