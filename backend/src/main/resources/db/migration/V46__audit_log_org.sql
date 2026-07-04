-- ============================================================================
-- V46: organization scoping for the audit log (#316).
--
-- audit_log is workspace-scoped (V10), but org-level actions (org.member.*,
-- org.sso_config.*, org.allowed_domain.*) authorize against org membership,
-- decoupled from the active workspace — so they were only visible in whichever
-- workspace the actor happened to have active, with no org-level audit surface.
-- This adds an immutable `org_id` stamped at write time (the target org for
-- org-entity actions, else the active workspace's org), so an org administrator
-- can read their organization's audit trail directly.
--
-- Nullable and un-backfilled: auth/system events have no org (as they have no
-- workspace), and the append-only trigger forbids UPDATE, so historical rows keep
-- a null org_id; the org view is forward-looking from this migration.
-- ============================================================================

ALTER TABLE audit_log
    ADD COLUMN org_id INT NULL COMMENT 'Organization the event belongs to (null for pre-workspace/system events)' AFTER workspace_id,
    ADD CONSTRAINT fk_audit_log_org FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE SET NULL,
    ADD INDEX idx_audit_log_org (org_id, created_at);
