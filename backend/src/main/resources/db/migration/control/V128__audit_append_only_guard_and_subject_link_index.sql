-- V126 drops trg_audit_log_no_update, runs five auto-committed statements, and
-- only then recreates it. A half-application that an operator repairs by hand
-- can therefore leave audit_log updateable with no trace. Re-assert the guard
-- forward-only; it is a no-op on every catalog where V126 completed.
DROP TRIGGER IF EXISTS trg_audit_log_no_update;

CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE ON audit_log
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';

-- Workspace teardown counts open APPI requests linked to the workspace before
-- destroying anything and clears the subject link of the retained records under
-- the workspace lock. Both statements are keyed by (org_id, subject_workspace_id).
ALTER TABLE data_subject_request
    ADD INDEX idx_data_subject_request_org_subject_workspace
        (org_id, subject_workspace_id);
