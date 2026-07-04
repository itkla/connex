-- ----------------------------------------------------------------------------
-- V44: organization membership / org-level roles (#316, #313).
--
-- The organization (V22) is the customer / billing / breach boundary, but until
-- now it had no administrator of its own — all authority was proxied through
-- workspace roles, which let a workspace owner reach org-scoped configuration
-- (SSO) it should not. `org_member` is the org control plane: the roster of
-- users with org-level authority (`owner` or `admin`), distinct from workspace
-- membership. Org-scoped operations (SSO configuration today; billing, domains,
-- provisioning later) authorize against this table, not workspace permissions.
--
-- Backfill: existing organizations are 1:1 with a workspace (or the seeded
-- default org), so every active workspace owner becomes an org owner of the
-- organization that workspace belongs to. Idempotent via the composite PK.
-- ----------------------------------------------------------------------------

CREATE TABLE org_member (
    org_id      INT NOT NULL COMMENT 'Organization ID',
    user_id     INT NOT NULL COMMENT 'Member user ID',
    org_role    VARCHAR(16) NOT NULL DEFAULT 'admin' COMMENT 'Org role: owner | admin',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Membership creation timestamp',
    PRIMARY KEY (org_id, user_id),
    CONSTRAINT fk_org_member_org FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE,
    CONSTRAINT fk_org_member_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    INDEX idx_org_member_user (user_id, org_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Organization memberships (org-level administrators)';

INSERT INTO org_member (org_id, user_id, org_role)
SELECT DISTINCT w.org_id, wm.user_id, 'owner'
FROM workspace w
JOIN workspace_member wm ON wm.workspace_id = w.id
WHERE wm.role = 'owner' AND wm.status = 'active';
