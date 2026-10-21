ALTER TABLE workspace_member
    ADD COLUMN authorization_epoch BIGINT NOT NULL DEFAULT 1,
    ALGORITHM=INSTANT;

ALTER TABLE workspace_role
    ADD COLUMN permission_epoch BIGINT NOT NULL DEFAULT 1,
    ALGORITHM=INSTANT;

CREATE TABLE organization_ai_standing_approval_policy (
    org_id INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    allowed_tool_mask INT NOT NULL DEFAULT 0,
    maximum_daily_cap INT NOT NULL DEFAULT 100,
    maximum_per_run_cap INT NOT NULL DEFAULT 10,
    state_version BIGINT NOT NULL DEFAULT 1,
    kill_epoch BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (org_id),
    CONSTRAINT fk_ai_standing_approval_policy_org FOREIGN KEY (org_id)
        REFERENCES organization (id) ON DELETE CASCADE,
    CONSTRAINT chk_ai_standing_approval_policy_tools CHECK (allowed_tool_mask BETWEEN 0 AND 15),
    CONSTRAINT chk_ai_standing_approval_policy_caps CHECK (
        maximum_daily_cap BETWEEN 1 AND 10000
        AND maximum_per_run_cap BETWEEN 1 AND maximum_daily_cap),
    CONSTRAINT chk_ai_standing_approval_policy_versions CHECK (state_version > 0 AND kill_epoch > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
