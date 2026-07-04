package ooo.klae.connex.backend.beans;

import lombok.Data;

/**
 * A link between an external IdP identity and a Connex user. The tuple
 * ({@code provider}, {@code issuer}, {@code externalSubject}) is the stable
 * IdP-side key a returning SSO login is matched against; {@code orgId} records
 * which organization's connection minted the link. Written at login time (P2).
 */
@Data
public class FederatedIdentity {
    private int id;
    private int userId;
    private int orgId;
    private String provider;
    private String issuer;
    private String externalSubject;
    private String createdAt;
    private String lastLoginAt;
}
