CREATE TRIGGER IF NOT EXISTS trg_audit_log_no_update_v129 BEFORE UPDATE ON audit_log
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';

ALTER TABLE data_subject_request
    ADD INDEX idx_data_subject_request_org_subject_workspace
        (org_id, subject_workspace_id);

CREATE TABLE tenant_export_admission_control (
    id TINYINT NOT NULL,
    capacity INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_tenant_export_admission_singleton CHECK (id = 1),
    CONSTRAINT chk_tenant_export_admission_capacity CHECK (capacity BETWEEN 1 AND 4)
) DEFAULT CHARSET=utf8mb4
    COMMENT='Database-global tenant export admission mutex and capacity';

INSERT INTO tenant_export_admission_control (id, capacity)
VALUES (1, 4);
