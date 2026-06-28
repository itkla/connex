-- ============================================================================
-- Automation rules: a trigger (an entity-change event OR a time-based schedule)
-- combined with an optional WHEN condition (the shared segment condition model)
-- and one or more THEN actions. A rule executes either as a workspace member
-- (run-as user) or as the workspace system automation actor. rule_execution is
-- the per-fire log providing idempotency (a unique dedupe key per rule) and an
-- audit trail of what each fire did.
-- ============================================================================

CREATE TABLE rule (
    id              INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Rule ID',
    workspace_id    INT NOT NULL COMMENT 'Owning workspace',
    name            VARCHAR(128) NOT NULL COMMENT 'Human-readable rule name',
    description     VARCHAR(512) NULL COMMENT 'Optional description',
    enabled         BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'Whether the rule is active',
    record_type     VARCHAR(16) NOT NULL COMMENT 'Entity the rule concerns: company|person|deal|task',
    trigger_type    VARCHAR(16) NOT NULL COMMENT 'entity_change | schedule',
    trigger_config  JSON NOT NULL COMMENT 'Event specifics (events, target stage) or schedule cadence',
    condition_json  JSON NULL COMMENT 'Optional WHEN: a SegmentDefinition; null = match always',
    actions_json    JSON NOT NULL COMMENT 'THEN: ordered list of {type, config}',
    execution_mode  VARCHAR(8) NOT NULL COMMENT 'user | system',
    run_as_user_id  INT NULL COMMENT 'Acting member for execution_mode=user',
    created_by_id   INT NULL COMMENT 'Member who created the rule',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_rule_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    CONSTRAINT fk_rule_run_as_user FOREIGN KEY (run_as_user_id) REFERENCES app_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_rule_created_by FOREIGN KEY (created_by_id) REFERENCES app_user(id) ON DELETE SET NULL,
    INDEX idx_rule_workspace (workspace_id),
    INDEX idx_rule_dispatch (workspace_id, trigger_type, enabled)
) DEFAULT CHARSET=utf8mb4 COMMENT='Automation rules (trigger + WHEN condition + THEN actions)';

CREATE TABLE rule_execution (
    id                  INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Execution ID',
    workspace_id        INT NOT NULL COMMENT 'Owning workspace',
    rule_id             INT NOT NULL COMMENT 'Rule that fired',
    trigger_entity_type VARCHAR(16) NULL COMMENT 'Triggering entity type, for entity-change fires',
    trigger_entity_id   INT NULL COMMENT 'Triggering entity id, for entity-change fires',
    status              VARCHAR(16) NOT NULL COMMENT 'matched | skipped | failed',
    dedupe_key          VARCHAR(255) NOT NULL COMMENT 'Idempotency key for this fire',
    detail              JSON NULL COMMENT 'Per-action outcomes and/or error',
    executed_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'When the rule was evaluated',
    CONSTRAINT fk_rule_execution_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE,
    CONSTRAINT fk_rule_execution_rule FOREIGN KEY (rule_id) REFERENCES rule(id) ON DELETE CASCADE,
    UNIQUE KEY uq_rule_execution_dedupe (rule_id, dedupe_key),
    INDEX idx_rule_execution_rule (rule_id, executed_at)
) DEFAULT CHARSET=utf8mb4 COMMENT='Per-fire log of rule evaluations (idempotency + audit)';
