-- Workspace-configurable disqualification vocabulary (#559).
--
-- Existing workspaces remain row-less until their first edit. The service resolves that state to
-- the built-in vocabulary, then materialises every built-in with null labels on the first write.
CREATE TABLE disqualification_reason (
    id            INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Disqualification reason ID',
    workspace_id  INT NOT NULL COMMENT 'Owning workspace ID',
    code          VARCHAR(32)
                      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
                      NOT NULL COMMENT 'Stable value stored on contacts and lifecycle history',
    label         VARCHAR(200) NULL
                      COMMENT 'Workspace label; null uses the localized built-in label',
    requires_note BOOLEAN NOT NULL DEFAULT FALSE
                      COMMENT 'Whether a note is required for a new disqualification',
    position      INT NOT NULL DEFAULT 0 COMMENT 'Display order',
    built_in      BOOLEAN NOT NULL DEFAULT FALSE
                      COMMENT 'Whether this row materialised a built-in reason',
    archived_at   DATETIME NULL COMMENT 'When the reason was retired for new disqualifications',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_disqualification_reason_workspace_id (workspace_id, id),
    UNIQUE KEY uq_disqualification_reason_workspace_code (workspace_id, code),
    INDEX idx_disqualification_reason_workspace_position (workspace_id, archived_at, position)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Per-workspace disqualification vocabulary';
