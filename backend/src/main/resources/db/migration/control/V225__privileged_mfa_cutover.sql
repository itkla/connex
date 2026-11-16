-- ============================================================================
-- Privileged MFA trust cutover (#1534, slice 4a-2c).
--
-- Owner decision 1 (2026-09-27) trusts only passkeys held by accounts
-- privileged at cutover and makes existing org owners founders, so their future
-- passkeys receive FOUNDER coverage; decision 2 (2026-10-08) scopes that trust
-- to each active organization where the account is privileged. Stale founder
-- flags on non-owners are cleared, including memberships undergoing teardown.
--
-- Coverage outside that population is removed regardless of its source;
-- provenance and grant references inside it survive. Missing population pairs
-- receive GRANDFATHERED coverage. Account-wide BREAK_GLASS assurance is untouched.
-- Every open approval code is revoked without naming an account as revoker.
--
-- DML only: Flyway executes all four steps in one transaction. Stop every old
-- backend before migration; older binaries must never serve after this cutover,
-- because they permit unvetted approvals while enforcement remains dark.
-- ============================================================================

UPDATE org_member
SET founder = (org_role = 'owner')
WHERE founder <> (org_role = 'owner');

DELETE a
FROM privileged_credential_attestation a
LEFT JOIN webauthn_credential c ON c.id = a.credential_row_id
LEFT JOIN webauthn_user_entity e ON e.id = c.user_entity_user_id
LEFT JOIN (
-- BEGIN PRIVILEGED CUTOVER POPULATION
    SELECT om.user_id, o.id AS org_id
    FROM org_member om
    JOIN organization o ON o.id = om.org_id AND o.lifecycle_state = 'active'
    UNION ALL
    SELECT wm.user_id, o.id AS org_id
    FROM workspace_member wm
    JOIN workspace w ON w.id = wm.workspace_id AND w.lifecycle_state = 'active'
    JOIN organization o ON o.id = w.org_id AND o.lifecycle_state = 'active'
    WHERE wm.status = 'active'
      AND wm.role_id IS NULL
      AND wm.role IN ('admin', 'owner')
    UNION ALL
    SELECT wm.user_id, o.id AS org_id
    FROM workspace_member wm
    JOIN workspace w ON w.id = wm.workspace_id AND w.lifecycle_state = 'active'
    JOIN organization o ON o.id = w.org_id AND o.lifecycle_state = 'active'
    JOIN workspace_role_permission wrp ON wrp.workspace_role_id = wm.role_id
    WHERE wm.status = 'active'
      AND wrp.permission IN (
        'API_CREDENTIAL_MANAGE', 'AUDIT_READ', 'CAMPAIGN_MANAGE', 'CAMPAIGN_SEND', 'CONSENT_MANAGE',
        'CUSTOM_FIELD_MANAGE', 'DOCUMENT_MANAGE', 'MEMBER_MANAGE', 'PIPELINE_MANAGE',
        'PRODUCT_MANAGE', 'ROLE_MANAGE', 'RULE_MANAGE', 'SEQUENCE_MANAGE',
        'SHARE_MANAGE', 'TAG_MANAGE', 'TEAM_MANAGE', 'WORKSPACE_SETTINGS'
      )
-- END PRIVILEGED CUTOVER POPULATION
) privileged ON privileged.user_id = e.user_id AND privileged.org_id = a.org_id
WHERE privileged.org_id IS NULL;

INSERT INTO privileged_credential_attestation (credential_row_id, org_id, source, attested_at)
SELECT DISTINCT c.id, privileged.org_id, 'GRANDFATHERED', UTC_TIMESTAMP(3)
FROM webauthn_credential c
JOIN webauthn_user_entity e ON e.id = c.user_entity_user_id
JOIN (
-- BEGIN PRIVILEGED CUTOVER POPULATION
    SELECT om.user_id, o.id AS org_id
    FROM org_member om
    JOIN organization o ON o.id = om.org_id AND o.lifecycle_state = 'active'
    UNION ALL
    SELECT wm.user_id, o.id AS org_id
    FROM workspace_member wm
    JOIN workspace w ON w.id = wm.workspace_id AND w.lifecycle_state = 'active'
    JOIN organization o ON o.id = w.org_id AND o.lifecycle_state = 'active'
    WHERE wm.status = 'active'
      AND wm.role_id IS NULL
      AND wm.role IN ('admin', 'owner')
    UNION ALL
    SELECT wm.user_id, o.id AS org_id
    FROM workspace_member wm
    JOIN workspace w ON w.id = wm.workspace_id AND w.lifecycle_state = 'active'
    JOIN organization o ON o.id = w.org_id AND o.lifecycle_state = 'active'
    JOIN workspace_role_permission wrp ON wrp.workspace_role_id = wm.role_id
    WHERE wm.status = 'active'
      AND wrp.permission IN (
        'API_CREDENTIAL_MANAGE', 'AUDIT_READ', 'CAMPAIGN_MANAGE', 'CAMPAIGN_SEND', 'CONSENT_MANAGE',
        'CUSTOM_FIELD_MANAGE', 'DOCUMENT_MANAGE', 'MEMBER_MANAGE', 'PIPELINE_MANAGE',
        'PRODUCT_MANAGE', 'ROLE_MANAGE', 'RULE_MANAGE', 'SEQUENCE_MANAGE',
        'SHARE_MANAGE', 'TAG_MANAGE', 'TEAM_MANAGE', 'WORKSPACE_SETTINGS'
      )
-- END PRIVILEGED CUTOVER POPULATION
) privileged ON privileged.user_id = e.user_id
ON DUPLICATE KEY UPDATE privileged_credential_attestation.source = privileged_credential_attestation.source;

UPDATE privileged_mfa_attestation_grant
SET revoked_at = UTC_TIMESTAMP(6)
WHERE redeemed_at IS NULL AND revoked_at IS NULL;
