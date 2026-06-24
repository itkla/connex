-- ============================================================================
-- Membership approval: a member added by an admin starts as 'pending' and only
-- becomes a real member once they accept. Auth/role checks count only 'active'
-- rows; the settings roster still lists pending rows so admins see them.
-- ============================================================================

ALTER TABLE workspace_member
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'active' AFTER role;
