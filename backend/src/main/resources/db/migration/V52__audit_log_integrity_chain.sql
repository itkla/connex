-- ============================================================================
-- V52: tamper-evident audit-log integrity chain (#91).
--
-- Existing audit rows remain nullable because the table is append-only and cannot
-- be backfilled without violating the invariant. New rows are chained per review
-- scope so workspace and org exports can be verified without exposing other
-- tenants' audit rows.
-- ============================================================================

ALTER TABLE audit_log
    ADD COLUMN chain_scope_type VARCHAR(16) NULL COMMENT 'Integrity-chain scope: workspace, organization, or system' AFTER request_id,
    ADD COLUMN chain_scope_id INT NULL COMMENT 'Integrity-chain scope id; 0 for system scope' AFTER chain_scope_type,
    ADD COLUMN chain_index BIGINT NULL COMMENT 'Monotonic per-scope integrity-chain index' AFTER chain_scope_id,
    ADD COLUMN prev_hash CHAR(64) NULL COMMENT 'Previous row hash in the same integrity scope' AFTER chain_index,
    ADD COLUMN row_hash CHAR(64) NULL COMMENT 'HMAC-SHA-256 over the canonical audit row payload' AFTER prev_hash,
    ADD UNIQUE KEY uq_audit_log_chain_scope_index (chain_scope_type, chain_scope_id, chain_index),
    ADD INDEX idx_audit_log_integrity_scope (chain_scope_type, chain_scope_id, chain_index);

CREATE TABLE audit_log_integrity_head (
    scope_type VARCHAR(16) NOT NULL COMMENT 'Integrity-chain scope: workspace, organization, or system',
    scope_id INT NOT NULL COMMENT 'Integrity-chain scope id; 0 for system scope',
    next_chain_index BIGINT NOT NULL DEFAULT 1 COMMENT 'Next chain index to assign',
    current_hash CHAR(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000' COMMENT 'Current tail row hash, or the genesis hash',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last head update time',
    PRIMARY KEY (scope_type, scope_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Per-scope audit-log integrity heads';
