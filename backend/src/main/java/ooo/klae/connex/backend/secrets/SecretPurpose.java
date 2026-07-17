package ooo.klae.connex.backend.secrets;

/**
 * Approved database-backed secret slots. Adding a new integration secret should
 * extend this catalog instead of creating feature-local encryption logic.
 */
public enum SecretPurpose {
    WORKSPACE_SMTP_PASSWORD("workspace", "workspace.smtp.password"),
    WORKSPACE_DELIVERY_PROVIDER_CREDENTIAL("workspace", "workspace.delivery.provider_credential"),
    WORKSPACE_DELIVERY_WEBHOOK_SECRET("workspace", "workspace.delivery.webhook_secret"),
    ORG_SSO_OIDC_CLIENT_SECRET("organization", "org.sso.oidc_client_secret"),
    ORG_SSO_SAML_SP_PRIVATE_KEY("organization", "org.sso.saml_sp_private_key"),
    ORG_AI_PROVIDER_CREDENTIAL("organization", "org.ai.provider_credential");

    private final String scopeType;
    private final String value;

    SecretPurpose(String scopeType, String value) {
        this.scopeType = scopeType;
        this.value = value;
    }

    public String scopeType() {
        return scopeType;
    }

    public String value() {
        return value;
    }
}
