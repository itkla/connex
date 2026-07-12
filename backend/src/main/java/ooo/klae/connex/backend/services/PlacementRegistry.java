package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.OrgPlacement;
import ooo.klae.connex.backend.mappers.OrgPlacementMapper;
import ooo.klae.connex.backend.tenant.DatabaseHandles;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;

/**
 * Read-only access to the per-organization deployment placement registry
 * (#313 Phase 3). Resolves an organization's placement, falling back to a
 * synthetic {@code shared} placement when none is persisted so that existing
 * organizations behave unchanged. This bean performs no routing or datasource
 * selection; {@code TenantCatalogResolver} consumes it to do so.
 */
@Service
@RequiredArgsConstructor
public class PlacementRegistry {

    private static final Logger log = LoggerFactory.getLogger(PlacementRegistry.class);

    private final OrgPlacementMapper orgPlacementMapper;
    private final TenantRoutingProperties routingProperties;

    /**
     * Resolves the placement for a membership-validated organization. The
     * {@code shared} fallback is a compatibility default for a real org that has
     * no registry row yet; callers must pass an org id already validated against
     * membership (as {@code TenantResolutionInterceptor} derives it), never a raw
     * client value. Routing must use {@link #effectivePlacementFor(int)} instead,
     * which verifies org existence before applying the fallback.
     *
     * @param orgId the membership-validated organization to resolve placement for
     * @return the persisted placement, or a synthetic {@code shared} placement
     *     when the organization has no registry row
     * @throws IllegalArgumentException when {@code orgId} is not a positive id
     */
    public OrgPlacement placementFor(int orgId) {
        requirePositive(orgId);
        OrgPlacement placement = orgPlacementMapper.findByOrg(orgId);
        return placement != null ? placement : OrgPlacement.sharedDefault(orgId);
    }

    /**
     * Existence-verified placement for the routing path. Every resolution reads
     * {@code organization LEFT JOIN org_placement} directly so all application
     * instances observe placement changes from the same control-plane source of
     * truth. This intentionally costs one indexed lookup per tenant-scope
     * installation: a per-JVM cache can leave different instances writing to
     * different catalogs during a placement change. Each installed scope then
     * keeps that result for its whole logical operation; an eventual live
     * cutover still requires a write fence, drain, final sync, and activation
     * protocol before dedicated routing can be enabled.
     *
     * @param orgId the membership-validated organization to resolve
     * @return the effective placement ({@code shared}-shaped when no row exists),
     *     or {@code null} when the organization itself does not exist
     * @throws IllegalArgumentException when {@code orgId} is not a positive id
     */
    public OrgPlacement effectivePlacementFor(int orgId) {
        requirePositive(orgId);
        OrgPlacement loaded = orgPlacementMapper.findEffectiveByOrg(orgId);
        if (loaded != null && loaded.getPlacementMode() == null) {
            loaded = OrgPlacement.sharedDefault(orgId);
        }
        return loaded;
    }

    /**
     * The catalogs background sweeps must fan out over: the default catalog
     * (represented as {@code null}) plus every distinct dedicated-database
     * handle when catalog routing is enabled. In {@code single-database} mode
     * only the default catalog is returned — dedicated placements are refused
     * fail-closed on the request path and receive no background processing
     * either (#485). Handles are validated through {@code DatabaseHandles} so
     * a malformed or reserved registry row is skipped (and logged) rather than
     * pinned verbatim.
     *
     * @return the catalogs to sweep; {@code null} means the default catalog
     */
    public List<String> activeCatalogs() {
        List<String> catalogs = new ArrayList<>();
        catalogs.add(null);
        if (routingProperties.isCatalogPerPlacement()) {
            for (String handle : orgPlacementMapper.distinctDedicatedHandles()) {
                if (DatabaseHandles.servable(handle, routingProperties.getDefaultCatalog())) {
                    catalogs.add(handle);
                } else {
                    log.warn("Skipping unservable placement handle '{}' in the catalog sweep", handle);
                }
            }
        }
        return catalogs;
    }

    private void requirePositive(int orgId) {
        if (orgId <= 0) {
            throw new IllegalArgumentException("orgId must be a positive organization id");
        }
    }
}
