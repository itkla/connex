package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

/**
 * Persistence for the per-organization email-domain allowlist that constrains
 * workspace invites (#316, Option B). Org-scoped control plane, not workspace-scoped.
 */
public interface OrgAllowedDomainMapper {
    List<String> findByOrg(@Param("orgId") int orgId);
    int add(@Param("orgId") int orgId, @Param("domain") String domain);
    int remove(@Param("orgId") int orgId, @Param("domain") String domain);
    boolean isAllowed(@Param("orgId") int orgId, @Param("domain") String domain);
    int countByOrg(@Param("orgId") int orgId);
}
