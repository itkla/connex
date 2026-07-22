package ooo.klae.connex.backend.mappers;

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
    Integer lockById(int id);
    Integer lockByIdForShare(int id);
}
