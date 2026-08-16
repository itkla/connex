-- Contact lead-lifecycle state (#559). The decision to model the lifecycle on the contact record
-- instead of a separate lead entity is recorded in docs/LEAD_LIFECYCLE.md.
--
-- lifecycle_stage is deliberately nullable: every contact that predates this feature, and every
-- contact captured as a relationship rather than a prospect, is genuinely not in a lead lifecycle.
-- Backfilling an invented stage would misrepresent the whole existing contact base.
ALTER TABLE person
    ADD COLUMN lifecycle_stage VARCHAR(16)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
        NULL COMMENT 'Lead lifecycle stage; NULL when the contact is not in a lead lifecycle',
    ADD COLUMN lifecycle_changed_at DATETIME NULL
        COMMENT 'When the lifecycle stage last changed',
    ADD COLUMN disqualified_reason VARCHAR(32)
        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
        NULL COMMENT 'Reason code required while the contact is disqualified',
    ADD COLUMN qualification_notes VARCHAR(2000) NULL
        COMMENT 'Free-text qualification or disqualification notes',
    ADD CONSTRAINT chk_person_lifecycle_stage CHECK (
        lifecycle_stage IS NULL OR lifecycle_stage IN (
            'NEW', 'WORKING', 'NURTURING', 'QUALIFIED', 'DISQUALIFIED', 'CONVERTED', 'RECYCLED')),
    -- A reason code is meaningless unless the contact is currently disqualified; transitions out of
    -- DISQUALIFIED must clear it. The reason survives in person_lifecycle_history either way.
    ADD CONSTRAINT chk_person_disqualified_reason CHECK (
        disqualified_reason IS NULL OR lifecycle_stage = 'DISQUALIFIED'),
    ADD INDEX idx_person_workspace_lifecycle_stage (workspace_id, lifecycle_stage);

-- Append-only transition log. It backs the contact's lifecycle timeline and is the source for
-- first-response, qualification-rate, and time-to-convert reporting, so rows are never updated or
-- deleted while the contact exists. changed_by_id references a control-plane user and therefore
-- carries no foreign key: the plane wall forbids it.
CREATE TABLE person_lifecycle_history (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Lifecycle transition row ID',
    workspace_id  INT NOT NULL COMMENT 'Owning workspace ID',
    person_id     INT NOT NULL COMMENT 'Contact whose lifecycle changed',
    from_stage    VARCHAR(16)
                      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                      NULL COMMENT 'Stage before the transition; NULL when entering the lifecycle',
    to_stage      VARCHAR(16)
                      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                      NULL COMMENT 'Stage after the transition; NULL when withdrawing',
    reason        VARCHAR(32)
                      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                      NULL COMMENT 'Disqualification reason code recorded with the transition',
    note          VARCHAR(2000) NULL COMMENT 'Free-text note recorded with the transition',
    changed_by_id INT NULL COMMENT 'Acting control-plane user ID; no FK across the plane wall',
    changed_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'When the transition happened',
    CONSTRAINT chk_person_lifecycle_history_from_stage CHECK (
        from_stage IS NULL OR from_stage IN (
            'NEW', 'WORKING', 'NURTURING', 'QUALIFIED', 'DISQUALIFIED', 'CONVERTED', 'RECYCLED')),
    CONSTRAINT chk_person_lifecycle_history_to_stage CHECK (
        to_stage IS NULL OR to_stage IN (
            'NEW', 'WORKING', 'NURTURING', 'QUALIFIED', 'DISQUALIFIED', 'CONVERTED', 'RECYCLED')),
    CONSTRAINT chk_person_lifecycle_history_change CHECK (
        from_stage IS NULL OR to_stage IS NULL OR from_stage <> to_stage),
    UNIQUE KEY uq_person_lifecycle_history_workspace_id (workspace_id, id),
    INDEX idx_person_lifecycle_history_workspace_person (workspace_id, person_id, changed_at, id),
    CONSTRAINT fk_person_lifecycle_history_person
        FOREIGN KEY (workspace_id, person_id)
        REFERENCES person(workspace_id, id) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Append-only lead-lifecycle transition log for contacts';
