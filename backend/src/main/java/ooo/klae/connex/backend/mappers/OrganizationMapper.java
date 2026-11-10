package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.dto.ExportPermissions;
import ooo.klae.connex.backend.beans.Organization;

/**
 * Mapper for organization persistence (the top-level tenant boundary above
 * workspace). Not workspace-scoped — organizations sit above the workspace
 * tenancy stack — so it is intentionally absent from
 * {@code TenantScopeInterceptor.SCOPED_NAMESPACES}.
 */
public interface OrganizationMapper {
    int insert(Organization organization);
    Organization getById(int id);
    Organization getActiveById(int id);
    Organization lockActiveIdentity(int id);
    int updateName(@Param("id") int id, @Param("name") String name);
    Integer lockById(int id);
    Integer lockActiveById(int id);
    int lockDuplicateDecision(int id);
    Integer lockByIdForShare(int id);
    Integer lockActiveByIdForShare(int id);
    /** Returns export access for built-in roles in this exact control-plane scope. */
    ExportPermissions getExportPermissions(@Param("id") int id);
    /** Replaces only the named built-in role's export access. */
    int updateExportPermission(@Param("id") int id, @Param("role") String role,
        @Param("enabled") boolean enabled);
}
