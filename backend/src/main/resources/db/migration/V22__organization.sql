-- ----------------------------------------------------------------------------
-- V22: organization layer (#97, Phase 0).
--
-- Introduces `organization` as the top-level tenant / billing / breach boundary
-- above `workspace`. This phase only adds the object and the workspace link;
-- per-entity org_id scoping (making org_id the primary discriminator on every
-- business table) is Phase 1, rolled out behind a flag.
--
-- Existing workspaces and any bare workspace insert fall back to the seeded
-- default organization (id 1); `WorkspaceService.createWorkspace` creates a
-- dedicated organization per new workspace (1:1 today).
-- ----------------------------------------------------------------------------

CREATE TABLE organization (
    id          INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Organization ID',
    name        VARCHAR(128) NOT NULL COMMENT 'Organization name',
    slug        VARCHAR(128) NOT NULL UNIQUE COMMENT 'Organization slug',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp'
) DEFAULT CHARSET=utf8mb4 COMMENT='Organizations (top-level tenant boundary above workspace)';

-- The default organization (id 1) that existing workspaces and bare inserts use.
INSERT INTO organization (name, slug) VALUES ('Default Organization', 'default');

ALTER TABLE workspace ADD COLUMN org_id INT NOT NULL DEFAULT 1 AFTER id;

ALTER TABLE workspace
    ADD CONSTRAINT fk_workspace_org FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE RESTRICT,
    ADD INDEX idx_workspace_org (org_id);
