package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.Data;
import ooo.klae.connex.backend.beans.SsoConnection;

/**
 * An organization's SSO connection as returned to the client. The OIDC client
 * secret is never included; {@code hasClientSecret} reports whether one is stored
 * so the UI can show a "configured" state and leave the field blank to keep it
 * unchanged. {@code configured} is false when no connection exists yet.
 */
@Data
public class SsoConnectionDto {
    private boolean configured;
    private String protocol;
    private boolean enabled;
    private boolean enforceSso;
    private Integer jitWorkspaceId;
    private String defaultRole;
    private String oidcIssuer;
    private String oidcClientId;
    private boolean hasClientSecret;
    private String oidcScopes;
    private String samlIdpEntityId;
    private String samlSsoUrl;
    private String samlIdpMetadataXml;
    private String samlIdpX509;
    private List<String> domains;
    private String updatedAt;

    /**
     * Maps a stored connection and its routing domains to the client view, omitting
     * the encrypted client secret.
     * @param connection the stored connection, or null when none exists
     * @param domains the org's SSO routing domains
     * @return the DTO (an empty, unconfigured default when connection is null)
     */
    public static SsoConnectionDto from(SsoConnection connection, List<String> domains) {
        SsoConnectionDto dto = new SsoConnectionDto();
        dto.setDomains(domains);
        if (connection == null) {
            dto.setOidcScopes("openid,email,profile");
            dto.setDefaultRole("member");
            return dto;
        }
        dto.setConfigured(true);
        dto.setProtocol(connection.getProtocol());
        dto.setEnabled(connection.isEnabled());
        dto.setEnforceSso(connection.isEnforceSso());
        dto.setJitWorkspaceId(connection.getJitWorkspaceId());
        dto.setDefaultRole(connection.getDefaultRole());
        dto.setOidcIssuer(connection.getOidcIssuer());
        dto.setOidcClientId(connection.getOidcClientId());
        dto.setHasClientSecret(connection.getOidcClientSecretEnc() != null
                && !connection.getOidcClientSecretEnc().isBlank());
        dto.setOidcScopes(connection.getOidcScopes());
        dto.setSamlIdpEntityId(connection.getSamlIdpEntityId());
        dto.setSamlSsoUrl(connection.getSamlSsoUrl());
        dto.setSamlIdpMetadataXml(connection.getSamlIdpMetadataXml());
        dto.setSamlIdpX509(connection.getSamlIdpX509());
        dto.setUpdatedAt(connection.getUpdatedAt());
        return dto;
    }
}
