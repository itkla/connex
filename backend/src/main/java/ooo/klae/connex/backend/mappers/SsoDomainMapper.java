package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

/**
 * Data access for the email-domain to organization SSO routing map. Globally
 * unique domains (distinct from the per-workspace allow-list); reads at login time
 * resolve a domain to its owning organization. Control-plane, scoped by explicit,
 * permission-gated {@code orgId} in {@code SsoConnectionService}, so it is
 * intentionally NOT in {@code TenantScopeInterceptor.SCOPED_NAMESPACES}.
 * SQL lives in {@code resources/mappers/SsoDomainMapper.xml}.
 */
public interface SsoDomainMapper {

    Integer lockMutationRoot();

    Integer findOrgByDomain(String domain);

    List<String> listByOrg(int orgId);

    int insert(@Param("domain") String domain, @Param("orgId") int orgId);

    int delete(String domain);
}
