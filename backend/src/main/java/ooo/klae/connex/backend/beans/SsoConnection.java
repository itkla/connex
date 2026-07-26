package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.ToString;

/**
 * A single organization's SSO/IdP connection (one row per org). Holds the shared
 * routing/provisioning settings plus the protocol-specific OIDC or SAML fields.
 * The OIDC client secret is stored encrypted at rest ({@code oidcClientSecretEnc});
 * the raw value never leaves the service layer and is never returned to a client.
 */
@Data
@ToString(exclude = { "oidcClientSecretEnc", "samlSpPrivateKeyEnc" })
public class SsoConnection {
    private int id;
    private int orgId;
    private String protocol;
    private boolean enabled;
    private boolean enforceSso;
    private Integer jitWorkspaceId;
    private String defaultRole;
    private String oidcIssuer;
    private String oidcClientId;
    private String oidcClientSecretEnc;
    private String oidcScopes;
    private String samlIdpEntityId;
    private String samlSsoUrl;
    private String samlIdpMetadataXml;
    private String samlIdpX509;
    private String samlSpPrivateKeyEnc;
    private String samlSpCertificate;
    private String createdAt;
    private String updatedAt;
}
