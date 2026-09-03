CREATE TABLE team (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    workspace_id    INT NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(1000) NULL,
    -- Intentionally no FK to control-plane app_user: tenant-plane tables must not reference the control plane; dangling values are tolerated and removed during offboarding. See docs/backend/SECURITY_BOUNDARIES.md.
    manager_user_id INT NULL,
    archived_at     DATETIME(6) NULL,
    live_name       VARCHAR(128) GENERATED ALWAYS AS (
        CASE WHEN archived_at IS NULL THEN name ELSE NULL END
    ) VIRTUAL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_team_name
        CHECK (CHAR_LENGTH(TRIM(name)) BETWEEN 1 AND 128),
    CONSTRAINT chk_team_description
        CHECK (description IS NULL OR CHAR_LENGTH(TRIM(description)) BETWEEN 1 AND 1000),

    UNIQUE KEY uq_team_workspace_id (workspace_id, id),
    UNIQUE KEY uq_team_live_name (workspace_id, live_name),
    INDEX idx_team_workspace (workspace_id),
    INDEX idx_team_manager_user (manager_user_id)
) DEFAULT CHARSET=utf8mb4;

CREATE TABLE team_member (
    workspace_id INT NOT NULL,
    team_id      INT NOT NULL,
    -- Intentionally no FK to control-plane app_user: tenant-plane tables must not reference the control plane; dangling values are tolerated and removed during offboarding. See docs/backend/SECURITY_BOUNDARIES.md.
    user_id      INT NOT NULL,
    role         VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'member',
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT chk_team_member_role CHECK (role IN ('member', 'manager')),
    CONSTRAINT fk_team_member_team
        FOREIGN KEY (workspace_id, team_id)
        REFERENCES team(workspace_id, id)
        ON DELETE CASCADE,

    PRIMARY KEY (workspace_id, team_id, user_id),
    INDEX idx_team_member_workspace_user (workspace_id, user_id),
    INDEX idx_team_member_user (user_id)
) DEFAULT CHARSET=utf8mb4;
