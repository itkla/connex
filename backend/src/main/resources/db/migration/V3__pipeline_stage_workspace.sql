-- ============================================================================
-- Pipeline becomes workspace-owned (shareable, like company). Stage inherits its
-- workspace from the parent pipeline via a denormalized workspace_id so it can be
-- scoped directly. Composite stage->pipeline FK is deferred to the constraints
-- phase; for now the application keeps stage.workspace_id = pipeline.workspace_id.
-- ============================================================================

-- Backfill pre-existing rows (pre-Flyway schema) to the first workspace; stage
-- inherits its workspace from its parent pipeline. Empty on a fresh DB.
ALTER TABLE pipeline ADD COLUMN workspace_id INT NOT NULL DEFAULT 0 AFTER id;
UPDATE pipeline SET workspace_id = (SELECT id FROM workspace ORDER BY id LIMIT 1) WHERE EXISTS (SELECT 1 FROM workspace);
ALTER TABLE pipeline
    ALTER COLUMN workspace_id DROP DEFAULT,
    ADD CONSTRAINT fk_pipeline_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    ADD UNIQUE KEY uq_pipeline_workspace_id (workspace_id, id),
    ADD INDEX idx_pipeline_workspace (workspace_id);

ALTER TABLE stage ADD COLUMN workspace_id INT NOT NULL DEFAULT 0 AFTER id;
UPDATE stage s JOIN pipeline p ON s.pipeline_id = p.id SET s.workspace_id = p.workspace_id;
ALTER TABLE stage
    ALTER COLUMN workspace_id DROP DEFAULT,
    ADD CONSTRAINT fk_stage_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT,
    ADD UNIQUE KEY uq_stage_workspace_id (workspace_id, id),
    ADD INDEX idx_stage_workspace (workspace_id);

-- Cross-workspace shares of a pipeline; the owner remains pipeline.workspace_id.
CREATE TABLE pipeline_share (
    pipeline_id  INT NOT NULL COMMENT 'Shared pipeline ID',
    workspace_id INT NOT NULL COMMENT 'Workspace the pipeline is shared with',
    granted_by   INT NULL COMMENT 'User who granted the share',
    can_edit     BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Whether the grantee workspace may edit',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Share creation timestamp',
    PRIMARY KEY (pipeline_id, workspace_id),
    CONSTRAINT fk_pipeline_share_pipeline   FOREIGN KEY (pipeline_id)  REFERENCES pipeline(id)  ON DELETE CASCADE,
    CONSTRAINT fk_pipeline_share_workspace  FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    CONSTRAINT fk_pipeline_share_granted_by FOREIGN KEY (granted_by)   REFERENCES app_user(id)  ON DELETE SET NULL,
    INDEX idx_pipeline_share_workspace (workspace_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Cross-workspace pipeline shares';
