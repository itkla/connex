CREATE INDEX idx_workspace_member_role_status
    ON workspace_member (workspace_id, role, status);
