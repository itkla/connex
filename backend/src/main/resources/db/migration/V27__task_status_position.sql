-- ============================================================================
-- Tasks gain an explicit workflow status (To Do / In Progress / Done) and a
-- manual sort order within their status column, for the Kanban board. status is
-- a VARCHAR + CHECK (matching the codebase convention — no native ENUMs), and
-- 'done' is kept in lockstep with the existing `completed` flag by the service
-- layer; the CHECK below makes any missed dual-write fail loudly. position is
-- contiguous and 0-based per (workspace_id, status), like deal.position.
-- ============================================================================

ALTER TABLE task ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'todo' COMMENT 'Workflow status: todo | in_progress | done' AFTER completed;
ALTER TABLE task ADD COLUMN position INT NOT NULL DEFAULT 0 COMMENT 'Manual sort order within the status column (0-based, contiguous)' AFTER status;

-- Existing completed tasks become 'done'; the rest stay 'todo'.
UPDATE task SET status = 'done' WHERE completed = TRUE;

-- Seed an order for pre-existing tasks within each (workspace, status) column.
UPDATE task t
JOIN (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY workspace_id, status ORDER BY due_date IS NULL, due_date, id
    ) - 1 AS rn
    FROM task
) r ON r.id = t.id
SET t.position = r.rn;

ALTER TABLE task
    ADD CONSTRAINT chk_task_status CHECK (status IN ('todo', 'in_progress', 'done')),
    ADD CONSTRAINT chk_task_status_completed CHECK ((status = 'done') = (completed = TRUE)),
    ADD INDEX idx_task_status_position (workspace_id, status, position);
