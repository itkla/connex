package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * Upsert of an organization's SSO connection. Structural constraints only; the
 * "required per protocol" and secret-encryption rules live in
 * {@code SsoConnectionService}. A null/blank {@code oidcClientSecret} leaves any
 * stored client secret unchanged; a blank string is never persisted as the secret.
 */
@Data
public class SsoConnectionRequest {

    @NotBlank
    @Pattern(regexp = "oidc|saml", message = "protocol must be oidc or saml")
    private String protocol;

    private boolean enabled;

    private boolean enforceSso;

    @NotNull
    private Integer jitWorkspaceId;

    @Size(max = 16)
    private String defaultRole;

    @Size(max = 512)
    private String oidcIssuer;

    @Size(max = 255)
    private String oidcClientId;

    @Size(max = 512)
    private String oidcClientSecret;

    @Size(max = 255)
    private String oidcScopes;

    @Size(max = 512)
    private String samlIdpEntityId;

    @Size(max = 512)
    private String samlSsoUrl;

    private String samlIdpMetadataXml;

    private String samlIdpX509;

    private List<@Size(max = 255) String> domains;
}
