package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

/**
 * Data access for the per-workspace email-domain allowlist. Control-plane: every statement is
 * scoped by an explicit, permission-gated {@code workspaceId} in {@code AllowedDomainService}, so
 * it is intentionally NOT in {@code TenantScopeInterceptor.SCOPED_NAMESPACES} (mirrors InviteMapper).
 * SQL lives in {@code resources/mappers/AllowedDomainMapper.xml}.
 */
public interface AllowedDomainMapper {
    List<String> findByWorkspace(int workspaceId);

    int add(@Param("workspaceId") int workspaceId, @Param("domain") String domain);

    int remove(@Param("workspaceId") int workspaceId, @Param("domain") String domain);

    boolean isAllowed(@Param("workspaceId") int workspaceId, @Param("domain") String domain);

    int countByWorkspace(int workspaceId);
}
