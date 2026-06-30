-- ============================================================================
-- introduction : the "give side" of the relationship graph. Where person_edge
-- records who already knows whom, this records introductions the team MAKES
-- between two of its contacts — turning the user into the connector that
-- positions contact A to meet contact B (issue #43, reverse introductions).
--
-- A 'made' row is intro lineage: who was introduced, by which member, and when.
-- A 'dismissed' row suppresses a suggested pair the user judged not worth
-- introducing. Either status removes the pair from the suggestion candidate set.
-- Pairs are unordered, so each is stored once with the smaller id as person_a
-- (person_a_id < person_b_id), mirroring person_edge.
-- ============================================================================

CREATE TABLE introduction (
    id                 INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Introduction ID',
    workspace_id       INT NOT NULL COMMENT 'Owning workspace',
    introducer_user_id INT NOT NULL COMMENT 'Member who recorded the intro (or dismissed the suggestion)',
    person_a_id        INT NOT NULL COMMENT 'Lower-id endpoint of the introduced pair',
    person_b_id        INT NOT NULL COMMENT 'Higher-id endpoint of the introduced pair',
    status             VARCHAR(16) NOT NULL DEFAULT 'made' COMMENT 'made | dismissed',
    note               VARCHAR(500) NULL COMMENT 'Optional context for the introduction',
    introduced_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'When the intro was made (or the pair dismissed)',
    created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Row creation timestamp',
    updated_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Row update timestamp',
    CONSTRAINT fk_introduction_workspace  FOREIGN KEY (workspace_id)       REFERENCES workspace(id) ON DELETE RESTRICT,
    CONSTRAINT fk_introduction_introducer FOREIGN KEY (introducer_user_id) REFERENCES app_user(id)  ON DELETE RESTRICT,
    CONSTRAINT fk_introduction_person_a   FOREIGN KEY (person_a_id)        REFERENCES person(id)    ON DELETE CASCADE,
    CONSTRAINT fk_introduction_person_b   FOREIGN KEY (person_b_id)        REFERENCES person(id)    ON DELETE CASCADE,
    UNIQUE KEY uq_introduction_pair (workspace_id, person_a_id, person_b_id),
    INDEX idx_introduction_lineage (workspace_id, status, introduced_at),
    INDEX idx_introduction_person_a (workspace_id, person_a_id),
    INDEX idx_introduction_person_b (workspace_id, person_b_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Introductions the team makes between two contacts (reverse-intro lineage)';
