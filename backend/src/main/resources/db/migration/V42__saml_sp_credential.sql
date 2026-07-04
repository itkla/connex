-- ============================================================================
-- SAML service-provider signing credential (#296 P5). Enterprise IdPs (Keycloak,
-- Shibboleth, HENNGE) commonly require the SP to sign its AuthnRequests, so each
-- SAML connection gets a self-signed SP key pair generated on save. The private key
-- is AES-GCM encrypted at rest with connex.sso.secret-key (like the OIDC client
-- secret) and never leaves the service layer; the certificate is public — the admin
-- hands it to the IdP so it can verify the SP's signed requests.
-- ============================================================================

ALTER TABLE sso_connection
    ADD COLUMN saml_sp_private_key_enc MEDIUMTEXT NULL
        COMMENT 'AES-GCM encrypted PKCS#8 SP signing private key' AFTER saml_idp_x509,
    ADD COLUMN saml_sp_certificate MEDIUMTEXT NULL
        COMMENT 'Self-signed SP signing certificate (PEM, public)' AFTER saml_sp_private_key_enc;
