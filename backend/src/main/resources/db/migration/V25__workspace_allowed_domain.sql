-- ============================================================================
-- workspace_allowed_domain : an owner-managed allowlist of email domains that may
-- join a workspace through the broad self-serve channels (invite links today; the
-- future on-prem domain-signup mode). An EMPTY allowlist means unrestricted — the
-- default — so existing workspaces are unaffected. Explicit email invites are NOT
-- gated by this list (they are a deliberate per-person owner decision).
-- ============================================================================

CREATE TABLE workspace_allowed_domain (
    workspace_id INT NOT NULL COMMENT 'Workspace the allowlist applies to',
    domain       VARCHAR(255) NOT NULL COMMENT 'Allowed email domain, normalized lowercase, no leading @',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    PRIMARY KEY (workspace_id, domain),
    CONSTRAINT fk_workspace_allowed_domain_workspace FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE
) DEFAULT CHARSET=utf8mb4 COMMENT='Per-workspace allowed email domains for self-serve joins';
