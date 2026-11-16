ALTER TABLE rule_execution
    ADD INDEX idx_rule_execution_workspace_history
        (workspace_id, executed_at DESC, id DESC),
    ALGORITHM=INPLACE, LOCK=NONE;
