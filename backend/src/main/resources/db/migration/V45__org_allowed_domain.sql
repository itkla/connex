-- ============================================================================
-- org_allowed_domain : an org-administrator-managed allowlist of email domains
-- that may be invited into ANY workspace of the organization. It is the org-level
-- ceiling on membership (#316, Option B): when set, every workspace invite/join in
-- the org — link redemption, email-token accept, and admin-adds-existing — is
-- constrained to these domains, on top of (AND-ed with) any per-workspace
-- workspace_allowed_domain (V25). An EMPTY org allowlist means unrestricted — the
-- default — so existing organizations and workspaces are unaffected and keep
-- operating exactly as before until an org sets a policy.
--
-- Distinct from sso_domain (V39): that routes SSO logins for a globally-unique
-- domain to an org's IdP and carries login side effects; this is a plain invite
-- ceiling that every org can use whether or not SSO is configured.
-- ============================================================================

CREATE TABLE org_allowed_domain (
    org_id      INT NOT NULL COMMENT 'Organization the allowlist applies to',
    domain      VARCHAR(255) NOT NULL COMMENT 'Allowed email domain, normalized lowercase, no leading @',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    PRIMARY KEY (org_id, domain),
    CONSTRAINT fk_org_allowed_domain_org FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE
) DEFAULT CHARSET=utf8mb4 COMMENT='Per-organization allowed email domains constraining workspace invites';
