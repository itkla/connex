-- ============================================================================
-- Consumer social login (Sign in with Google / Microsoft, #314). Social identities
-- are instance-wide, not scoped to an organization, so org_id becomes nullable on the
-- federation tables. Enterprise per-org rows keep a non-null org_id and their org-scoped
-- lookups (org_id = ?) naturally exclude these null social rows; social rows are keyed by
-- (provider='google'|'microsoft', issuer, external_subject) with a null org.
-- ============================================================================

ALTER TABLE federated_identity MODIFY COLUMN org_id INT NULL
    COMMENT 'Organization whose connection minted this link; NULL for consumer social login';

ALTER TABLE sso_link_challenge MODIFY COLUMN org_id INT NULL
    COMMENT 'Organization the linked identity belongs to; NULL for consumer social login';
