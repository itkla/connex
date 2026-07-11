package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.OrgPlacement;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.services.PlacementRegistry;

@ExtendWith(MockitoExtension.class)
class TenantCatalogResolverTest {

    @Mock private PlacementRegistry placementRegistry;

    private TenantRoutingProperties properties;
    private TenantCatalogResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new TenantRoutingProperties();
        resolver = new TenantCatalogResolver(placementRegistry, properties);
    }

    @Test
    void sharedPlacementResolvesToDefaultCatalog() {
        when(placementRegistry.effectivePlacementFor(7)).thenReturn(OrgPlacement.sharedDefault(7));
        assertNull(resolver.resolveCatalog(7));
    }

    @Test
    void missingOrganizationIsRefused() {
        when(placementRegistry.effectivePlacementFor(7)).thenReturn(null);
        assertThrows(ServiceUnavailableException.class, () -> resolver.resolveCatalog(7));
    }

    @Test
    void dedicatedPlacementIsRefusedInSingleDatabaseMode() {
        when(placementRegistry.effectivePlacementFor(7)).thenReturn(dedicated("cnx_abc123"));
        assertThrows(ServiceUnavailableException.class, () -> resolver.resolveCatalog(7));
    }

    @Test
    void dedicatedPlacementRoutesInCatalogMode() {
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        when(placementRegistry.effectivePlacementFor(7)).thenReturn(dedicated("cnx_abc123"));
        assertEquals("cnx_abc123", resolver.resolveCatalog(7));
    }

    @Test
    void siloPlacementIsRefusedInBothModes() {
        OrgPlacement silo = dedicated("cnx_abc123");
        silo.setPlacementMode("connex_operated_silo");
        when(placementRegistry.effectivePlacementFor(7)).thenReturn(silo);

        assertThrows(ServiceUnavailableException.class, () -> resolver.resolveCatalog(7));
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        assertThrows(ServiceUnavailableException.class, () -> resolver.resolveCatalog(7));
    }

    @Test
    void malformedDatabaseHandleIsRefused() {
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        when(placementRegistry.effectivePlacementFor(7)).thenReturn(dedicated("cnx_x; DROP DATABASE"));
        assertThrows(ServiceUnavailableException.class, () -> resolver.resolveCatalog(7));
    }

    @Test
    void databaseHandleEqualToDefaultCatalogIsRefused() {
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog("connexdb");
        when(placementRegistry.effectivePlacementFor(7)).thenReturn(dedicated("ConnexDB"));
        assertThrows(ServiceUnavailableException.class, () -> resolver.resolveCatalog(7));
    }

    @Test
    void databaseHandleTargetingSystemCatalogIsRefused() {
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        when(placementRegistry.effectivePlacementFor(7)).thenReturn(dedicated("mysql"));
        assertThrows(ServiceUnavailableException.class, () -> resolver.resolveCatalog(7));
    }

    @Test
    void missingDatabaseHandleIsRefused() {
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        when(placementRegistry.effectivePlacementFor(7)).thenReturn(dedicated(null));
        assertThrows(ServiceUnavailableException.class, () -> resolver.resolveCatalog(7));
    }

    @Test
    void unknownPlacementModeIsRefused() {
        OrgPlacement unknown = dedicated("cnx_abc123");
        unknown.setPlacementMode("future_tier");
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        when(placementRegistry.effectivePlacementFor(7)).thenReturn(unknown);
        assertThrows(ServiceUnavailableException.class, () -> resolver.resolveCatalog(7));
    }

    private OrgPlacement dedicated(String handle) {
        OrgPlacement placement = OrgPlacement.sharedDefault(7);
        placement.setPlacementMode("dedicated_database");
        placement.setDatabaseHandle(handle);
        return placement;
    }
}
