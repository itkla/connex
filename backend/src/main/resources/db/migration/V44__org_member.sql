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
-- Backfill: seed exactly ONE founding owner per organization — the owner of the
-- org's earliest workspace — rather than sweeping in every workspace owner.
-- Workspace ownership is deliberately NOT org authority (a workspace can have
-- several owners, and post-#318 an org can span several workspaces owned by
-- different people), so promoting all of them would re-open the very escalation
-- this closes. The founding owner adds further org admins explicitly via the
-- org API. The seeded default organization (id 1) is a shared catch-all for
-- legacy / bare-insert workspaces belonging to unrelated tenants, so it is
-- excluded entirely — granting anyone ownership of it would be cross-tenant.
-- Such workspaces are re-homed into their own orgs by a later migration.
--
-- Consequences (acceptable pre-launch, tracked as follow-ups): an org whose only
-- workspace owner is inactive gets no owner (operator-recoverable), and a
-- customer who delegated SSO via a custom `SSO_MANAGE` role must have that person
-- re-designated an org admin by the founding owner.
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
SELECT ranked.org_id, ranked.user_id, 'owner'
FROM (
    SELECT w.org_id AS org_id, wm.user_id AS user_id,
           ROW_NUMBER() OVER (
               PARTITION BY w.org_id
               ORDER BY w.created_at, w.id, wm.created_at, wm.user_id
           ) AS rn
    FROM workspace w
    JOIN workspace_member wm ON wm.workspace_id = w.id
    WHERE wm.role = 'owner' AND wm.status = 'active' AND w.org_id <> 1
) ranked
WHERE ranked.rn = 1;
