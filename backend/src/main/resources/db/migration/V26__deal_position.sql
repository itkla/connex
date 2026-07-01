-- ============================================================================
-- Deals gain a manual sort order within their stage column, for the Kanban
-- board. position is contiguous and 0-based per (workspace_id, stage_id); the
-- service layer keeps it dense inside a transaction. It is deliberately NOT
-- unique: deals reorder frequently and concurrently, and a unique constraint
-- would make the sibling-shift UPDATE order-sensitive and deadlock-prone.
-- ============================================================================

ALTER TABLE deal ADD COLUMN position INT NOT NULL DEFAULT 0 COMMENT 'Manual sort order within the stage column (0-based, contiguous)' AFTER stage_id;

-- Seed an order for pre-existing deals: stable by creation order within each
-- (workspace, stage) column. Empty on a fresh DB.
UPDATE deal d
JOIN (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY workspace_id, stage_id ORDER BY created_at, id
    ) - 1 AS rn
    FROM deal
) r ON r.id = d.id
SET d.position = r.rn;

ALTER TABLE deal ADD INDEX idx_deal_stage_position (workspace_id, stage_id, position);
