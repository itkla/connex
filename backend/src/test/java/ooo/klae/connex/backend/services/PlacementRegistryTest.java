package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.OrgPlacement;
import ooo.klae.connex.backend.mappers.OrgPlacementMapper;

@ExtendWith(MockitoExtension.class)
class PlacementRegistryTest {

    @Mock private OrgPlacementMapper orgPlacementMapper;
    @InjectMocks private PlacementRegistry placementRegistry;

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
    }
}
