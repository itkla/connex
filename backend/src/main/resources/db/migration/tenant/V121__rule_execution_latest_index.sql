ALTER TABLE rule_execution
    ADD INDEX idx_rule_execution_workspace_latest
        (workspace_id, rule_id, executed_at DESC, id DESC);
