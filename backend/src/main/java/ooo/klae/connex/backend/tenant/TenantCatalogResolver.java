package ooo.klae.connex.backend.tenant;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.OrgPlacement;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.services.PlacementRegistry;

/**
 * Fail-closed translation from an organization's placement to the catalog its
 * connections must use, evaluated once when the {@link TenantContext} is
 * installed (never on the connection checkout path, which only reads the
 * pinned result). A {@code shared} placement resolves to {@code null} (the
 * default catalog); anything this deployment cannot serve safely — a missing
 * org row, a silo placement, a dedicated placement without routing enabled, or
 * a malformed database handle — is refused with a 503 rather than silently
 * served from shared storage.
 */
@Component
@RequiredArgsConstructor
public class TenantCatalogResolver {

    private static final Pattern DATABASE_HANDLE = Pattern.compile("^[A-Za-z0-9_]{1,64}$");

    private final PlacementRegistry placementRegistry;
    private final TenantRoutingProperties properties;

    /**
     * Resolves the catalog for a membership-validated organization.
     *
     * @param orgId the organization the current scope belongs to
     * @return the catalog to pin for the request span, or {@code null} for the
     *     default (shared) catalog
     * @throws ServiceUnavailableException when the org's placement cannot be
     *     served safely by this deployment
     */
    public String resolveCatalog(int orgId) {
        OrgPlacement placement = placementRegistry.effectivePlacementFor(orgId);
        if (placement == null) {
            throw new ServiceUnavailableException(
                "Organization " + orgId + " has no placement record; refusing to serve it");
        }
        return switch (placement.getPlacementMode()) {
            case "shared" -> null;
            case "dedicated_database" -> dedicatedCatalog(orgId, placement);
            default -> throw new ServiceUnavailableException(
                "Organization " + orgId + " is not served by this deployment");
        };
    }

    private String dedicatedCatalog(int orgId, OrgPlacement placement) {
        if (!properties.isCatalogPerPlacement()) {
            throw new ServiceUnavailableException(
                "Organization " + orgId + " requires placement routing this deployment does not provide");
        }
        String handle = placement.getDatabaseHandle();
        if (handle == null || !DATABASE_HANDLE.matcher(handle).matches()) {
            throw new ServiceUnavailableException(
                "Organization " + orgId + " has an invalid placement database handle");
        }
        return handle;
    }
}
