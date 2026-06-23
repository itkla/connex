-- ============================================================================
-- Scope the audit log per workspace. NULLable: system/auth events (login,
-- register) happen before a workspace is resolved and stay workspace-less.
-- The append-only triggers only block UPDATE/DELETE on rows, not DDL.
-- ============================================================================

ALTER TABLE audit_log
    ADD COLUMN workspace_id INT NULL AFTER id,
    ADD CONSTRAINT fk_audit_log_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE SET NULL,
    DROP INDEX idx_audit_log_entity,
    ADD INDEX idx_audit_log_entity (workspace_id, entity_type, entity_id),
    ADD INDEX idx_audit_log_workspace (workspace_id, created_at);
