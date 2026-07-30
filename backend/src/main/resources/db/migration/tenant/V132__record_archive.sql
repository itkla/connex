ALTER TABLE person
    ADD COLUMN archived_at DATETIME NULL COMMENT 'Set when the contact is archived; NULL means active' AFTER provision_ceased_at,
    ADD INDEX idx_person_workspace_archived (workspace_id, archived_at);

ALTER TABLE company
    ADD COLUMN archived_at DATETIME NULL COMMENT 'Set when the company is archived; NULL means active' AFTER logo_url,
    ADD INDEX idx_company_workspace_archived (workspace_id, archived_at);
