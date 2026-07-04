-- ============================================================================
-- Federated identity links (#296 P2). One row per external IdP identity bound to
-- a Connex user: (provider, issuer, external_subject) is the stable IdP-side key
-- (OIDC iss+sub, or SAML entityId+NameID) that a returning SSO login is matched
-- against. org_id records which organization's connection minted the link so a
-- user carries a distinct identity per org. Populated at login time in P2; P1 only
-- defines the schema. A user row can hold several links (different orgs/providers),
-- so user_id is a non-unique index.
--
-- The (provider, issuer, external_subject) uniqueness index uses issuer/subject
-- prefixes (191 chars) because the full utf8mb4 triple exceeds InnoDB's 3072-byte
-- index-key limit; issuers and IdP subjects are far shorter than 191 characters in
-- practice, so the prefix still enforces the intended one-identity-per-subject rule.
-- ============================================================================

CREATE TABLE federated_identity (
    id                  INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Identity link ID',
    user_id             INT NOT NULL COMMENT 'Linked Connex user',
    org_id              INT NOT NULL COMMENT 'Organization whose connection minted this link',
    provider            VARCHAR(16) NOT NULL COMMENT 'oidc | saml',
    issuer              VARCHAR(512) NOT NULL COMMENT 'OIDC issuer URL or SAML IdP entityId',
    external_subject    VARCHAR(512) NOT NULL COMMENT 'OIDC sub or SAML NameID (stable IdP subject)',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Link creation timestamp',
    last_login_at       DATETIME NULL COMMENT 'Most recent SSO login through this link',
    CONSTRAINT fk_federated_identity_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_federated_identity_org FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE RESTRICT,
    UNIQUE KEY uq_federated_identity_subject (provider, issuer(191), external_subject(191)),
    KEY ix_federated_identity_user (user_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='External IdP identities linked to Connex users';
