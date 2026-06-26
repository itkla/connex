-- ============================================================================
-- person_employment : the trail of where a contact has worked. A contact's
-- current company lives on person.company_id; previously that value was
-- silently overwritten on change. This table preserves the history so a
-- champion who moves to a new company surfaces as a warm lead at the new
-- account. The row with ended_at IS NULL is the current employment.
-- company_name is snapshotted so the history survives company edits/deletes.
-- ============================================================================

CREATE TABLE person_employment (
    id           INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Employment record ID',
    workspace_id INT NOT NULL COMMENT 'Owning workspace',
    person_id    INT NOT NULL COMMENT 'Contact this employment belongs to',
    company_id   INT NULL COMMENT 'Company (nullable; SET NULL if the company is deleted)',
    company_name VARCHAR(255) NULL COMMENT 'Company name snapshot at record time',
    title        VARCHAR(128) NULL COMMENT 'Contact title while at this company',
    started_at   DATETIME NULL COMMENT 'When this employment began (best-effort, UTC)',
    ended_at     DATETIME NULL COMMENT 'When it ended; NULL means current',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Row creation timestamp',
    CONSTRAINT fk_person_employment_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    CONSTRAINT fk_person_employment_person FOREIGN KEY (person_id) REFERENCES person(id) ON DELETE CASCADE,
    CONSTRAINT fk_person_employment_company FOREIGN KEY (company_id) REFERENCES company(id) ON DELETE SET NULL,
    INDEX idx_person_employment_person (workspace_id, person_id),
    INDEX idx_person_employment_recent (workspace_id, ended_at, started_at)
) DEFAULT CHARSET=utf8mb4 COMMENT='Contact employment history';

-- Seed a current employment row for every contact that already has a company, so the
-- history starts from today's known state. company_name is snapshotted from company.
INSERT INTO person_employment (workspace_id, person_id, company_id, company_name, title, started_at, ended_at, created_at)
SELECT p.workspace_id, p.id, p.company_id, c.name, p.title, p.created_at, NULL, p.created_at
FROM person p
JOIN company c ON c.id = p.company_id
WHERE p.company_id IS NOT NULL;
