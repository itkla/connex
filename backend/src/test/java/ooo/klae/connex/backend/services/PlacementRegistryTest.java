package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

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
    void effectivePlacementIsCachedWithinTtl() {
        when(orgPlacementMapper.findEffectiveByOrg(7)).thenReturn(OrgPlacement.sharedDefault(7));

        placementRegistry.effectivePlacementFor(7);
        placementRegistry.effectivePlacementFor(7);

        verify(orgPlacementMapper, times(1)).findEffectiveByOrg(7);
    }

    @Test
    void missingOrgIsCachedToo() {
        when(orgPlacementMapper.findEffectiveByOrg(7)).thenReturn(null);

        assertNull(placementRegistry.effectivePlacementFor(7));
        assertNull(placementRegistry.effectivePlacementFor(7));

        verify(orgPlacementMapper, times(1)).findEffectiveByOrg(7);
    }

    @Test
    void invalidateForcesReload() {
        when(orgPlacementMapper.findEffectiveByOrg(7)).thenReturn(OrgPlacement.sharedDefault(7));

        placementRegistry.effectivePlacementFor(7);
        placementRegistry.invalidate(7);
        placementRegistry.effectivePlacementFor(7);

        verify(orgPlacementMapper, times(2)).findEffectiveByOrg(7);
    }

    @Test
    void zeroTtlDisablesCaching() {
        properties.setPlacementCacheTtl(Duration.ZERO);
        when(orgPlacementMapper.findEffectiveByOrg(7)).thenReturn(OrgPlacement.sharedDefault(7));

        placementRegistry.effectivePlacementFor(7);
        placementRegistry.effectivePlacementFor(7);

        verify(orgPlacementMapper, times(2)).findEffectiveByOrg(7);
    }

    @Test
    void absurdlyLargeTtlIsClampedAndDoesNotThrow() {
        properties.setPlacementCacheTtl(Duration.ofDays(200_000));
        when(orgPlacementMapper.findEffectiveByOrg(7)).thenReturn(OrgPlacement.sharedDefault(7));

        assertNotNull(placementRegistry.effectivePlacementFor(7));
        placementRegistry.effectivePlacementFor(7);

        verify(orgPlacementMapper, times(1)).findEffectiveByOrg(7);
    }
}
