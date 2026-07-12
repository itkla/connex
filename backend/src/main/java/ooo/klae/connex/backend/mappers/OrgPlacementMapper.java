package ooo.klae.connex.backend.mappers;

import java.util.List;

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
     * Existence-verified read for the routing path: {@code organization} LEFT
     * JOIN {@code org_placement}. An org with no placement row yields a result
     * whose {@code placementMode} is {@code null}; {@code PlacementRegistry}
     * substitutes {@link OrgPlacement#sharedDefault(int)} so the shared-tier
     * defaults live in exactly one place.
     *
     * @param orgId the organization to look up
     * @return the effective placement (null-mode when the org has no placement
     *     row), or {@code null} only when the organization itself does not exist
     */
    OrgPlacement findEffectiveByOrg(@Param("orgId") int orgId);

    /**
     * Persists a placement row. Used by tests and future provisioning; not yet
     * reachable from any request-scoped service.
     *
     * @param placement the placement to insert
     */
    void insert(OrgPlacement placement);

    /**
     * Every distinct catalog handle a dedicated-database placement points at.
     * Backs the scheduler catalog fan-out (#485): background sweeps must
     * enumerate each active catalog, not just the default one.
     *
     * @return the distinct dedicated catalog handles, sorted
     */
    List<String> distinctDedicatedHandles();
}
