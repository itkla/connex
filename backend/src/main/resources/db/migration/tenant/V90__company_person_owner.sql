ALTER TABLE company
    ADD COLUMN owner_id INT NULL COMMENT 'Owning workspace member User ID' AFTER workspace_id,
    ADD INDEX idx_company_owner (workspace_id, owner_id),
    ADD INDEX idx_company_owner_user_only (owner_id);

ALTER TABLE person
    ADD COLUMN owner_id INT NULL COMMENT 'Owning workspace member User ID' AFTER workspace_id,
    ADD INDEX idx_person_owner (workspace_id, owner_id),
    ADD INDEX idx_person_owner_user_only (owner_id);
