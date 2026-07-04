package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.FederatedIdentity;

/**
 * Data access for federated identity links. Used by the SSO login flow (P2) to
 * match a returning IdP identity to a Connex user and to record logins. Keyed by
 * the IdP-side identity tuple, not by tenant, so it is intentionally NOT in
 * {@code TenantScopeInterceptor.SCOPED_NAMESPACES}. SQL lives in
 * {@code resources/mappers/FederatedIdentityMapper.xml}.
 */
public interface FederatedIdentityMapper {

    FederatedIdentity findByProviderIssuerSubject(@Param("provider") String provider,
            @Param("issuer") String issuer, @Param("subject") String subject);

    FederatedIdentity findByOrgProviderIssuerSubject(@Param("orgId") int orgId,
            @Param("provider") String provider, @Param("issuer") String issuer,
            @Param("subject") String subject);

    int countByUserIdExcludingOrg(@Param("userId") int userId, @Param("orgId") int orgId);

    int insert(FederatedIdentity identity);

    int touchLogin(int id);
}
