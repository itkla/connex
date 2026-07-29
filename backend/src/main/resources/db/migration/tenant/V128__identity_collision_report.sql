CREATE TABLE identity_collision (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Collision membership row ID',
    workspace_id        INT NOT NULL COMMENT 'Owning workspace ID',
    person_identity_id  BIGINT NULL COMMENT 'Colliding person identity when this is a person collision',
    company_identity_id BIGINT NULL COMMENT 'Colliding company identity when this is a company collision',
    rebuilt_at          DATETIME NOT NULL COMMENT 'Timestamp of the deterministic report rebuild',
    CONSTRAINT chk_identity_collision_exactly_one_identity
        CHECK (
            (person_identity_id IS NOT NULL AND company_identity_id IS NULL)
            OR
            (person_identity_id IS NULL AND company_identity_id IS NOT NULL)
        ),
    UNIQUE KEY uq_identity_collision_workspace_id (workspace_id, id),
    UNIQUE KEY uq_identity_collision_workspace_person_identity
        (workspace_id, person_identity_id),
    UNIQUE KEY uq_identity_collision_workspace_company_identity
        (workspace_id, company_identity_id),
    CONSTRAINT fk_identity_collision_person_identity
        FOREIGN KEY (workspace_id, person_identity_id)
        REFERENCES person_identity(workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_identity_collision_company_identity
        FOREIGN KEY (workspace_id, company_identity_id)
        REFERENCES company_identity(workspace_id, id) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Deterministically rebuilt membership of cross-record identity collisions';
