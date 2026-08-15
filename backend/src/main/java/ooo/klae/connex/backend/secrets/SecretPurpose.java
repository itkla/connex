package ooo.klae.connex.backend.secrets;

/**
 * Approved database-backed secret slots. Adding a new integration secret should
 * extend this catalog instead of creating feature-local encryption logic.
 */
public enum SecretPurpose {
    WORKSPACE_SMTP_PASSWORD("workspace", "workspace.smtp.password"),
    WORKSPACE_DELIVERY_PROVIDER_CREDENTIAL("workspace", "workspace.delivery.provider_credential"),
    WORKSPACE_DELIVERY_WEBHOOK_SECRET("workspace", "workspace.delivery.webhook_secret"),
    WORKSPACE_CONNECTOR_CREDENTIAL("workspace", "workspace.connector.credential"),
    ORG_SSO_OIDC_CLIENT_SECRET("organization", "org.sso.oidc_client_secret"),
    ORG_SSO_SAML_SP_PRIVATE_KEY("organization", "org.sso.saml_sp_private_key"),
    ORG_AI_PROVIDER_CREDENTIAL("organization", "org.ai.provider_credential"),
    USER_PROVIDER_GOOGLE_TOKEN("user", "user.provider.google_token"),
    USER_PROVIDER_MICROSOFT_TOKEN("user", "user.provider.microsoft_token"),
    USER_PROVIDER_PKCE_VERIFIER("user", "user.provider.pkce_verifier"),
    USER_PROVIDER_MICROSOFT_PKCE_VERIFIER(
        "user", "user.provider.microsoft_pkce_verifier");

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
