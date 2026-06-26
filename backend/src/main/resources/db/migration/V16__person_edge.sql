-- ============================================================================
-- person_edge : the contact-to-contact relationship graph that powers warm
-- introductions ("how do I reach this person?"). Connex's org->user->company->
-- contact edges describe ownership; this adds the social layer between contacts
-- themselves. Edges are mutual, so each pair is stored once with the smaller id
-- as source (source_person_id < target_person_id) and traversed in both
-- directions. A shortest path from a contact the team already engages to the
-- target is the introduction chain.
-- ============================================================================

CREATE TABLE person_edge (
    id               INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Edge ID',
    workspace_id     INT NOT NULL COMMENT 'Owning workspace',
    source_person_id INT NOT NULL COMMENT 'Lower-id endpoint of the mutual connection',
    target_person_id INT NOT NULL COMMENT 'Higher-id endpoint of the mutual connection',
    type             VARCHAR(32) NOT NULL DEFAULT 'knows' COMMENT 'colleague | former_colleague | knows | friend',
    strength         INT NOT NULL DEFAULT 2 COMMENT 'Tie strength 1 (weak) .. 3 (strong)',
    note             VARCHAR(255) NULL COMMENT 'Optional context for the connection',
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    CONSTRAINT fk_person_edge_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    CONSTRAINT fk_person_edge_source FOREIGN KEY (source_person_id) REFERENCES person(id) ON DELETE CASCADE,
    CONSTRAINT fk_person_edge_target FOREIGN KEY (target_person_id) REFERENCES person(id) ON DELETE CASCADE,
    UNIQUE KEY uq_person_edge_pair (workspace_id, source_person_id, target_person_id),
    INDEX idx_person_edge_source (workspace_id, source_person_id),
    INDEX idx_person_edge_target (workspace_id, target_person_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Contact-to-contact connections (warm-intro graph)';
