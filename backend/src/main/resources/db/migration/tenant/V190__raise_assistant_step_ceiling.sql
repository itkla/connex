-- Raise the assistant step ceiling. The closing step is now structurally final-only, so a turn's
-- practical bounds are its deadline, its no-progress guard, and its token budgets; a small step
-- count only forced budget-exhausted turns. Rows still holding the old default (6) are lifted to
-- match the new default so existing workspaces benefit without an operator visit.
ALTER TABLE ai_workspace_governance
    DROP CHECK chk_ai_workspace_governance_max_steps;
ALTER TABLE ai_workspace_governance
    MODIFY assistant_max_steps TINYINT UNSIGNED NOT NULL DEFAULT 24,
    ADD CONSTRAINT chk_ai_workspace_governance_max_steps
        CHECK (assistant_max_steps BETWEEN 1 AND 48);
UPDATE ai_workspace_governance SET assistant_max_steps = 24 WHERE assistant_max_steps = 6;
