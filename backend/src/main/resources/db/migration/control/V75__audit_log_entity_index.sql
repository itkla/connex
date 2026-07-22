-- ============================================================================
-- Subject-scoped audit reads (APPI 開示等 disclosure export, #221) look up
-- audit_log by (entity_type, entity_id) across workspaces, but the only
-- entity index is workspace-prefixed (idx_audit_log_entity), so those reads
-- full-scan the unbounded append-only table. This index serves the seek and
-- the created_at ordering.
-- ============================================================================
ALTER TABLE audit_log
    ADD INDEX idx_audit_log_entity_created (entity_type, entity_id, created_at);
