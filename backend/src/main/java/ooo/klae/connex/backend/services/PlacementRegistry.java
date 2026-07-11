package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.OrgPlacement;
import ooo.klae.connex.backend.mappers.OrgPlacementMapper;

/**
 * Read-only access to the per-organization deployment placement registry
 * (#313 Phase 3). Resolves an organization's placement, falling back to a
 * synthetic {@code shared} placement when none is persisted so that existing
 * organizations behave unchanged. This bean performs no routing or datasource
 * selection; later increments consume it to do so.
 */
@Service
@RequiredArgsConstructor
public class PlacementRegistry {

    private final OrgPlacementMapper orgPlacementMapper;

    /**
     * Resolves the placement for a membership-validated organization. The
     * {@code shared} fallback is a compatibility default for a real org that has
     * no registry row yet; callers must pass an org id already validated against
     * membership (as {@code TenantResolutionInterceptor} derives it), never a raw
     * client value. A later routing increment that must fail closed should verify
     * org existence before relying on the fallback.
     *
     * @param orgId the membership-validated organization to resolve placement for
     * @return the persisted placement, or a synthetic {@code shared} placement
     *     when the organization has no registry row
     * @throws IllegalArgumentException when {@code orgId} is not a positive id
     */
    public OrgPlacement placementFor(int orgId) {
        if (orgId <= 0) {
            throw new IllegalArgumentException("orgId must be a positive organization id");
        }
        OrgPlacement placement = orgPlacementMapper.findByOrg(orgId);
        return placement != null ? placement : OrgPlacement.sharedDefault(orgId);
    }
}
