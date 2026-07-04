package ooo.klae.connex.backend.beans;

import lombok.Data;

/**
 * A link between an external IdP identity and a Connex user. The tuple
 * ({@code provider}, {@code issuer}, {@code externalSubject}) is the stable
 * IdP-side key a returning SSO login is matched against; {@code orgId} records
 * which organization's connection minted the link, and is {@code null} for consumer
 * social-login identities (Google/Microsoft), which are not org-scoped. Written at login time.
 */
@Data
public class FederatedIdentity {
    private int id;
    private int userId;
    private Integer orgId;
    private String provider;
    private String issuer;
    private String externalSubject;
    private String createdAt;
    private String lastLoginAt;
}
