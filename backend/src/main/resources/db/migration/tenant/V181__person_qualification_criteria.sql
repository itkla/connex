-- Configurable qualification criteria and per-contact answers (#559, increment 5 of
-- docs/LEAD_LIFECYCLE.md).
--
-- The epic requires deterministic scoring first, with fit and engagement kept as separate
-- dimensions. Both are expressed through one mechanism — a workspace-authored criterion carries the
-- dimension it belongs to — so a workspace configures one list and reads two scores, and there is no
-- second scoring language to keep in agreement with the first.
--
-- Scores are deliberately NOT stored. They are a pure function of the criteria and the answers, and
-- a stored copy would go stale the moment a criterion's weight changed or a criterion was archived,
-- leaving two contradictory truths about whether a contact is qualified.
CREATE TABLE qualification_criterion (
    id           INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Criterion ID',
    workspace_id INT NOT NULL COMMENT 'Owning workspace ID',
    label        VARCHAR(200) NOT NULL COMMENT 'Question the criterion asks, in the workspace language',
    dimension    VARCHAR(16)
                     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                     NOT NULL COMMENT 'FIT or ENGAGEMENT; the two are scored separately',
    weight       INT NOT NULL DEFAULT 1 COMMENT 'Relative contribution within its dimension',
    required     BOOLEAN NOT NULL DEFAULT FALSE
                     COMMENT 'A required criterion must be met before a contact can be qualified',
    position     INT NOT NULL DEFAULT 0 COMMENT 'Display order within the dimension',
    archived_at  DATETIME NULL
                     COMMENT 'When the criterion was retired; archived criteria never score or gate',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_qualification_criterion_dimension CHECK (dimension IN ('FIT', 'ENGAGEMENT')),
    -- A zero or negative weight would let a criterion sit in the list contributing nothing while
    -- appearing to matter, and an unbounded weight lets one criterion silently dominate a dimension.
    CONSTRAINT chk_qualification_criterion_weight CHECK (weight BETWEEN 1 AND 100),
    -- workspace_id deliberately carries no foreign key: `workspace` is a control-plane table and a
    -- constraint here would cross the plane wall (#440). Tenant scoping is enforced by
    -- TenantScopeInterceptor and the service layer, exactly as for the other org-data tables.
    --
    -- Composite uniqueness so an answer's FK can bind criterion and workspace together, making an
    -- answer that references another workspace's criterion structurally impossible.
    UNIQUE KEY uq_qualification_criterion_workspace_id (workspace_id, id),
    INDEX idx_qualification_criterion_workspace_dimension (workspace_id, dimension, position)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Per-workspace lead qualification criteria';

-- One answer per contact per criterion. Unlike custom field values, this is not an overlay a
-- grantee workspace can write: qualification is the owning workspace's own assessment of its own
-- pipeline, exactly like the lifecycle stage and its notes, so the person foreign key binds each
-- answer to the workspace that owns the contact.
CREATE TABLE person_qualification_answer (
    workspace_id   INT NOT NULL COMMENT 'Owning workspace ID',
    person_id      INT NOT NULL COMMENT 'Contact being assessed',
    criterion_id   INT NOT NULL COMMENT 'Criterion being answered',
    answer         VARCHAR(16)
                       CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                       NOT NULL COMMENT 'MET, NOT_MET, or UNKNOWN',
    answered_by_id INT NULL COMMENT 'Acting control-plane user ID; no FK across the plane wall',
    answered_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (workspace_id, person_id, criterion_id),
    CONSTRAINT chk_person_qualification_answer CHECK (answer IN ('MET', 'NOT_MET', 'UNKNOWN')),
    CONSTRAINT fk_person_qualification_answer_person
        FOREIGN KEY (workspace_id, person_id)
        REFERENCES person(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_person_qualification_answer_criterion
        FOREIGN KEY (workspace_id, criterion_id)
        REFERENCES qualification_criterion(workspace_id, id) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Per-contact answers to the workspace qualification criteria';
