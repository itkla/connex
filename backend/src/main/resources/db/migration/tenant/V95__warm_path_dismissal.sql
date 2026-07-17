-- ============================================================================
-- warm_path_dismissal : hides warm-introduction-path suggestions ("reach C via
-- B") the team has acted on or declined. A row with bridge_person_id NULL
-- dismisses every path to the target; a row with a bridge dismisses only that
-- avenue. status records why the path went away (dismissed vs accepted into a
-- follow-up task) so lineage stays auditable. Dismissals are workspace-scoped
-- like the introduction table they sit beside.
-- ============================================================================

CREATE TABLE warm_path_dismissal (
    id                   INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id         INT NOT NULL,
    target_person_id     INT NOT NULL,
    bridge_person_id     INT NULL,
    status               VARCHAR(16) NOT NULL DEFAULT 'dismissed',
    dismissed_by_user_id INT NOT NULL,
    created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_warm_path_dismissal_status CHECK (status IN ('dismissed', 'accepted')),
    CONSTRAINT chk_warm_path_dismissal_distinct
        CHECK (bridge_person_id IS NULL OR bridge_person_id <> target_person_id),
    CONSTRAINT fk_warm_path_dismissal_target
        FOREIGN KEY (target_person_id) REFERENCES person(id) ON DELETE CASCADE,
    CONSTRAINT fk_warm_path_dismissal_bridge
        FOREIGN KEY (bridge_person_id) REFERENCES person(id) ON DELETE CASCADE,
    UNIQUE KEY uq_warm_path_dismissal (workspace_id, target_person_id, bridge_person_id),
    INDEX idx_warm_path_dismissal_workspace (workspace_id, target_person_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Dismissed or accepted warm-intro-path suggestions';
