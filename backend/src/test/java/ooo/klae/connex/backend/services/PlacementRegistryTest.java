package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.OrgPlacement;
import ooo.klae.connex.backend.mappers.OrgPlacementMapper;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;

@ExtendWith(MockitoExtension.class)
class PlacementRegistryTest {

    @Mock private OrgPlacementMapper orgPlacementMapper;

    private TenantRoutingProperties properties;
    private PlacementRegistry placementRegistry;

    @BeforeEach
    void setUp() {
        properties = new TenantRoutingProperties();
        placementRegistry = new PlacementRegistry(orgPlacementMapper, properties);
    }

    @Test
    void unknownOrgResolvesToSharedDefault() {
        when(orgPlacementMapper.findByOrg(7)).thenReturn(null);

        OrgPlacement placement = placementRegistry.placementFor(7);

        assertEquals(7, placement.getOrgId());
        assertEquals("shared", placement.getPlacementMode());
        assertEquals("provider_managed", placement.getStorageEncryptionMode());
    }

    @Test
    void persistedPlacementIsReturnedVerbatim() {
        OrgPlacement stored = new OrgPlacement();
        stored.setOrgId(9);
        stored.setPlacementMode("connex_operated_silo");
        when(orgPlacementMapper.findByOrg(9)).thenReturn(stored);

        assertSame(stored, placementRegistry.placementFor(9));
    }

    @Test
    void nonPositiveOrgIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> placementRegistry.placementFor(0));
        assertThrows(IllegalArgumentException.class, () -> placementRegistry.placementFor(-3));
        assertThrows(IllegalArgumentException.class, () -> placementRegistry.effectivePlacementFor(0));
    }

    @Test
    void rowlessOrgSubstitutesTheSharedDefault() {
        OrgPlacement nullMode = new OrgPlacement();
        nullMode.setOrgId(7);
        when(orgPlacementMapper.findEffectiveByOrg(7)).thenReturn(nullMode);

        OrgPlacement effective = placementRegistry.effectivePlacementFor(7);

        assertEquals(7, effective.getOrgId());
        assertEquals("shared", effective.getPlacementMode());
        assertEquals("provider_managed", effective.getStorageEncryptionMode());
    }

    @Test
    void independentInstancesDoNotRetainPlacementValuesBetweenResolutions() {
        OrgPlacement shared = OrgPlacement.sharedDefault(7);
        OrgPlacement dedicated = OrgPlacement.sharedDefault(7);
        dedicated.setPlacementMode("dedicated_database");
        dedicated.setDatabaseHandle("cnx_cutover");
        AtomicReference<OrgPlacement> controlPlane = new AtomicReference<>(shared);
        when(orgPlacementMapper.findEffectiveByOrg(7)).thenAnswer(invocation -> controlPlane.get());
        PlacementRegistry siblingInstance = new PlacementRegistry(orgPlacementMapper, properties);

        assertSame(shared, placementRegistry.effectivePlacementFor(7));
        assertSame(shared, siblingInstance.effectivePlacementFor(7));

        controlPlane.set(dedicated);
        assertSame(dedicated, placementRegistry.effectivePlacementFor(7));
        assertSame(dedicated, siblingInstance.effectivePlacementFor(7));

        controlPlane.set(null);
        assertNull(placementRegistry.effectivePlacementFor(7));
        assertNull(siblingInstance.effectivePlacementFor(7));

        verify(orgPlacementMapper, times(6)).findEffectiveByOrg(7);
    }

    @Test
    void activeCatalogsIsDefaultOnlyInSingleDatabaseMode() {
        assertEquals(Collections.singletonList(null), placementRegistry.activeCatalogs());
    }

    @Test
    void activeCatalogsFansOutOverDedicatedHandlesInCatalogMode() {
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        when(orgPlacementMapper.distinctDedicatedHandles()).thenReturn(List.of("cnx_a", "cnx_b"));

        assertEquals(Arrays.asList(null, "cnx_a", "cnx_b"), placementRegistry.activeCatalogs());
    }

    @Test
    void activeCatalogsSkipsUnservableHandles() {
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog("connexdb");
        when(orgPlacementMapper.distinctDedicatedHandles())
            .thenReturn(List.of("mysql", "ConnexDB", "cnx_ok", "bad-handle"));

        assertEquals(Arrays.asList(null, "cnx_ok"), placementRegistry.activeCatalogs());
    }

}
