-- ============================================================================
-- V53: append-only audit-integrity checkpoints (#91).
--
-- V52 adds per-row chain metadata plus a mutable head row for concurrency. This
-- migration adds an append-only checkpoint history so truncation/head rewrites are
-- detectable, and rejects any future audit rows that bypass integrity metadata.
-- ============================================================================

CREATE TABLE audit_log_integrity_checkpoint (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Checkpoint ID',
    scope_type VARCHAR(16) NOT NULL COMMENT 'Integrity-chain scope: workspace, organization, or system',
    scope_id INT NOT NULL COMMENT 'Integrity-chain scope id; 0 for system scope',
    chain_index BIGINT NOT NULL COMMENT 'Checkpointed chain index',
    prev_hash CHAR(64) NOT NULL COMMENT 'Previous row hash for the checkpointed audit row',
    row_hash CHAR(64) NOT NULL COMMENT 'Checkpointed audit row hash',
    audit_log_id INT NOT NULL COMMENT 'Checkpointed audit event ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Checkpoint append time',
    CONSTRAINT fk_audit_integrity_checkpoint_audit_log FOREIGN KEY (audit_log_id) REFERENCES audit_log(id) ON DELETE RESTRICT,
    UNIQUE KEY uq_audit_integrity_checkpoint_scope_index (scope_type, scope_id, chain_index),
    UNIQUE KEY uq_audit_integrity_checkpoint_audit_log (audit_log_id),
    INDEX idx_audit_integrity_checkpoint_scope (scope_type, scope_id, created_at)
) DEFAULT CHARSET=utf8mb4 COMMENT='Append-only audit-log integrity checkpoints';

CREATE TRIGGER trg_audit_integrity_checkpoint_no_update BEFORE UPDATE ON audit_log_integrity_checkpoint
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log_integrity_checkpoint is append-only';
CREATE TRIGGER trg_audit_integrity_checkpoint_no_delete BEFORE DELETE ON audit_log_integrity_checkpoint
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log_integrity_checkpoint is append-only';

DELIMITER //
CREATE TRIGGER trg_audit_log_require_integrity BEFORE INSERT ON audit_log
FOR EACH ROW
BEGIN
    IF NEW.chain_scope_type IS NULL
        OR NEW.chain_scope_id IS NULL
        OR NEW.chain_index IS NULL
        OR NEW.prev_hash IS NULL
        OR NEW.row_hash IS NULL
        OR NEW.chain_scope_type NOT IN ('workspace', 'organization', 'system')
        OR CHAR_LENGTH(NEW.prev_hash) <> 64
        OR CHAR_LENGTH(NEW.row_hash) <> 64
        OR NEW.chain_index < 1
        OR (NEW.chain_scope_type = 'system' AND NEW.chain_scope_id <> 0)
        OR (NEW.chain_scope_type <> 'system' AND NEW.chain_scope_id < 1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log integrity fields are required';
    END IF;
END//
DELIMITER ;

CREATE TRIGGER trg_audit_log_checkpoint AFTER INSERT ON audit_log
FOR EACH ROW INSERT INTO audit_log_integrity_checkpoint
    (scope_type, scope_id, chain_index, prev_hash, row_hash, audit_log_id)
VALUES
    (NEW.chain_scope_type, NEW.chain_scope_id, NEW.chain_index, NEW.prev_hash, NEW.row_hash, NEW.id);
