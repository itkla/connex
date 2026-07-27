-- Wave 2 (#854): archive replaces hard delete for contacts and companies.
-- archived_at is a reversible tombstone, mirroring the suspended_at / provision_ceased_at
-- processing-restriction columns added in V76: ordinary reads exclude the row, restore clears it,
-- and every child row (consent history, identity, tags, custom field values, shares) survives
-- because nothing is DELETEd any more.
-- No archived_by_id column: app_user lives in the control plane, no foreign key may cross the
-- plane wall, and a denormalized user id would add an offboarding-redaction obligation for
-- information the audit log (person.archive / company.archive) already records.
-- The composite index mirrors idx_person_owner / idx_company_owner: workspace first, so the
-- archived_at IS NULL predicate that every visibility fragment now carries stays index-eligible.

ALTER TABLE person
    ADD COLUMN archived_at DATETIME NULL COMMENT 'Set when the contact is archived; NULL means active' AFTER provision_ceased_at,
    ADD INDEX idx_person_workspace_archived (workspace_id, archived_at);

ALTER TABLE company
    ADD COLUMN archived_at DATETIME NULL COMMENT 'Set when the company is archived; NULL means active' AFTER logo_url,
    ADD INDEX idx_company_workspace_archived (workspace_id, archived_at);
