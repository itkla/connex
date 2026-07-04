-- ============================================================================
-- Per-organization SSO/IdP configuration (#296 P1). One connection row per
-- organization (the V22 tenant/billing boundary); an enterprise customer = one org
-- = one IdP. Protocol-specific columns hold OIDC or SAML settings; client secrets and
-- SP private keys are AES-GCM encrypted at rest (connex.sso.secret-key), never returned
-- to a client. sso_domain maps an email domain to an organization for login-time IdP
-- routing (globally unique, distinct from the per-workspace workspace_allowed_domain V25).
-- ============================================================================

CREATE TABLE sso_connection (
    id                      INT AUTO_INCREMENT PRIMARY KEY COMMENT 'Connection ID',
    org_id                  INT NOT NULL COMMENT 'Owning organization (one connection per org)',
    protocol                VARCHAR(8) NOT NULL COMMENT 'oidc | saml',
    enabled                 BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Whether SSO login is live for this org',
    enforce_sso             BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Disable password login for this org''s users',
    jit_workspace_id        INT NOT NULL COMMENT 'Workspace new SSO users are provisioned into',
    default_role            VARCHAR(16) NOT NULL DEFAULT 'member' COMMENT 'Role granted on JIT provisioning',
    oidc_issuer             VARCHAR(512) COMMENT 'OIDC issuer URL (discovery)',
    oidc_client_id          VARCHAR(255) COMMENT 'OIDC client id',
    oidc_client_secret_enc  VARCHAR(1024) COMMENT 'AES-GCM encrypted OIDC client secret',
    oidc_scopes             VARCHAR(255) NOT NULL DEFAULT 'openid,email,profile' COMMENT 'Requested scopes (CSV)',
    saml_idp_entity_id      VARCHAR(512) COMMENT 'SAML IdP entityId (becomes federated_identity.issuer)',
    saml_sso_url            VARCHAR(512) COMMENT 'SAML IdP single sign-on URL',
    saml_idp_metadata_xml   MEDIUMTEXT COMMENT 'SAML IdP metadata XML (alternative to explicit fields)',
    saml_idp_x509           MEDIUMTEXT COMMENT 'SAML IdP signing certificate (PEM, public)',
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    updated_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last update timestamp',
    CONSTRAINT fk_sso_connection_org FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE,
    CONSTRAINT fk_sso_connection_workspace FOREIGN KEY (jit_workspace_id) REFERENCES workspace(id),
    UNIQUE KEY uq_sso_connection_org (org_id)
) DEFAULT CHARSET=utf8mb4 COMMENT='Per-organization SSO/IdP configuration';

CREATE TABLE sso_domain (
    domain      VARCHAR(255) NOT NULL PRIMARY KEY COMMENT 'Email domain, normalized lowercase, no leading @',
    org_id      INT NOT NULL COMMENT 'Organization this domain routes SSO to',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation timestamp',
    CONSTRAINT fk_sso_domain_org FOREIGN KEY (org_id) REFERENCES organization(id) ON DELETE CASCADE
) DEFAULT CHARSET=utf8mb4 COMMENT='Email-domain to organization routing for SSO login';
