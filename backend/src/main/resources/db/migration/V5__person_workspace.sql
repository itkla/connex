-- ============================================================================
-- Person (contact) becomes workspace-owned (shareable, like company). person.company_id
-- stays a plain FK (company is itself shareable, so a contact's company may live in
-- another workspace once sharing is enabled). uq(workspace_id, id) anchors
-- same-workspace composite FKs from activity/note/task/deal_person.
-- ============================================================================

ALTER TABLE person
    ADD COLUMN workspace_id INT NOT NULL AFTER id,
    ADD CONSTRAINT fk_person_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    ADD UNIQUE KEY uq_person_workspace_id (workspace_id, id),
    ADD INDEX idx_person_workspace (workspace_id),
    ADD INDEX idx_person_workspace_email (workspace_id, email);

-- Cross-workspace shares of a contact; the owner remains person.workspace_id.
CREATE TABLE person_share (
    person_id    INT NOT NULL COMMENT 'Shared person ID',
    workspace_id INT NOT NULL COMMENT 'Workspace the person is shared with',
    granted_by   INT NULL COMMENT 'User who granted the share',
    can_edit     BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Whether the grantee workspace may edit',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Share creation timestamp',
    PRIMARY KEY (person_id, workspace_id),
    CONSTRAINT fk_person_share_person    FOREIGN KEY (person_id)    REFERENCES person(id)    ON DELETE CASCADE,
    CONSTRAINT fk_person_share_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    CONSTRAINT fk_person_share_granted_by FOREIGN KEY (granted_by)  REFERENCES app_user(id)  ON DELETE SET NULL,
    INDEX idx_person_share_workspace (workspace_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Cross-workspace person shares';
