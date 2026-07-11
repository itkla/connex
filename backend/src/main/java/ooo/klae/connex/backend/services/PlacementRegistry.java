package ooo.klae.connex.backend.services;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.OrgPlacement;
import ooo.klae.connex.backend.mappers.OrgPlacementMapper;
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

    private static final long MAX_CACHE_TTL_NANOS = Duration.ofDays(365).toNanos();

    private record CachedEffective(OrgPlacement placement, long expiresAtNanos) {}

    private final ConcurrentHashMap<Integer, CachedEffective> effectiveCache = new ConcurrentHashMap<>();

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
     * Existence-verified placement for the routing path: one indexed read of
     * {@code organization LEFT JOIN org_placement}, cached per org for
     * {@code connex.tenancy.routing.placement-cache-ttl} (misses cached too, so
     * the request path costs at most one control-plane query per org per TTL
     * window). Any future placement write path must call {@link #invalidate(int)}.
     *
     * @param orgId the membership-validated organization to resolve
     * @return the effective placement ({@code shared}-shaped when no row exists),
     *     or {@code null} when the organization itself does not exist
     * @throws IllegalArgumentException when {@code orgId} is not a positive id
     */
    public OrgPlacement effectivePlacementFor(int orgId) {
        requirePositive(orgId);
        long now = System.nanoTime();
        CachedEffective cached = effectiveCache.get(orgId);
        if (cached != null && now - cached.expiresAtNanos() < 0) {
            return cached.placement();
        }
        OrgPlacement loaded = orgPlacementMapper.findEffectiveByOrg(orgId);
        effectiveCache.put(orgId, new CachedEffective(loaded, now + cacheTtlNanos()));
        return loaded;
    }

    private long cacheTtlNanos() {
        Duration ttl = routingProperties.getPlacementCacheTtl();
        long nanos;
        try {
            nanos = ttl.toNanos();
        } catch (ArithmeticException overflow) {
            return MAX_CACHE_TTL_NANOS;
        }
        if (nanos < 0) {
            return 0;
        }
        return Math.min(nanos, MAX_CACHE_TTL_NANOS);
    }

    /**
     * Drops the cached effective placement for one organization. Must be called
     * by any code path that writes {@code org_placement}.
     *
     * @param orgId the organization whose cached placement to drop
     */
    public void invalidate(int orgId) {
        effectiveCache.remove(orgId);
    }

    /** Drops every cached effective placement. */
    public void invalidateAll() {
        effectiveCache.clear();
    }

    private void requirePositive(int orgId) {
        if (orgId <= 0) {
            throw new IllegalArgumentException("orgId must be a positive organization id");
        }
    }
}
