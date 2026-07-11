package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.OrgPlacement;

/**
 * Control-plane mapper for the per-organization deployment placement registry
 * (#313 Phase 3). Read path plus a seed insert; there is no request-scoped write
 * surface yet. Never workspace-scoped — classified as control-plane in
 * {@code TenantScopeInterceptor}.
 */
public interface OrgPlacementMapper {

    /**
     * @param orgId the organization to look up
     * @return the persisted placement, or {@code null} when the org has none
     */
    OrgPlacement findByOrg(@Param("orgId") int orgId);

    /**
     * Persists a placement row. Used by tests and future provisioning; not yet
     * reachable from any request-scoped service.
     *
     * @param placement the placement to insert
     */
    void insert(OrgPlacement placement);
}
