package ooo.klae.connex.backend.beans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class OrgPlacementTest {
    @Test
    void sharedDefaultCarriesPooledPosture() {
        OrgPlacement placement = OrgPlacement.sharedDefault(42);
        assertEquals("shared", placement.getPlacementMode());
        assertEquals("provider_managed", placement.getStorageEncryptionMode());
        assertEquals("connex_cloud_provider", placement.getKeyController());
        assertFalse(placement.isRevocationSupported());
    }
}
