-- Per-workspace admission mutex for automation authoring (GitHub #1670).
-- Only workflow/rule authoring transactions lock this row, so serialising the aggregate
-- trigger-capacity count cannot block audited CRM writes, which take the workspace root
-- with a shared lock at commit time. See docs/backend/LOCKING.md step 3.
-- Rows are created lazily by the authoring path; the table is additive and rollback-safe
-- because no earlier application version reads or writes it.
CREATE TABLE workflow_trigger_admission (
    workspace_id INT NOT NULL PRIMARY KEY,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) DEFAULT CHARSET=utf8mb4;
