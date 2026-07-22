package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import ooo.klae.connex.backend.beans.OrgPlacement;
import ooo.klae.connex.backend.beans.Organization;

class OrgPlacementMapperTest extends AbstractMapperTest {
    @Autowired private OrgPlacementMapper orgPlacementMapper;
    @Autowired private OrganizationMapper organizationMapper;

    @Test
    void unknownOrgHasNoRow() {
        Organization org = newOrg();
        assertNull(orgPlacementMapper.findByOrg(org.getId()));
    }

    @Test
    void insertedPlacementRoundTrips() {
        Organization org = newOrg();
        OrgPlacement placement = new OrgPlacement();
        placement.setOrgId(org.getId());
        placement.setPlacementMode("connex_operated_silo");
        placement.setDatabaseHandle("cnx_" + unique());
        placement.setStorageEncryptionMode("customer_managed_cmk");
        placement.setKeyController("customer");
        placement.setKmsProvider("aws_kms");
        placement.setKmsKeyRef("arn:aws:kms:ap-northeast-1:111122223333:key/" + unique());
        placement.setKmsKeyRegion("ap-northeast-1");
        placement.setRevocationSupported(true);
        placement.setRevocationEffect("Disabling the key makes the silo unavailable.");

        orgPlacementMapper.insert(placement);

        OrgPlacement stored = orgPlacementMapper.findByOrg(org.getId());
        assertEquals("connex_operated_silo", stored.getPlacementMode());
        assertEquals(placement.getDatabaseHandle(), stored.getDatabaseHandle());
        assertEquals("customer_managed_cmk", stored.getStorageEncryptionMode());
        assertEquals("customer", stored.getKeyController());
        assertEquals("aws_kms", stored.getKmsProvider());
        assertEquals(placement.getKmsKeyRef(), stored.getKmsKeyRef());
        assertEquals("ap-northeast-1", stored.getKmsKeyRegion());
        assertTrue(stored.isRevocationSupported());
    }

    @Test
    void placementModeCheckConstraintRejectsUnknownTier() {
        Organization org = newOrg();
        OrgPlacement placement = OrgPlacement.sharedDefault(org.getId());
        placement.setPlacementMode("bogus_tier");
        assertThrows(DataAccessException.class, () -> orgPlacementMapper.insert(placement));
    }

    @Test
    void dedicatedPlacementRequiresDatabaseHandle() {
        Organization org = newOrg();
        OrgPlacement placement = OrgPlacement.sharedDefault(org.getId());
        placement.setPlacementMode("dedicated_database");
        placement.setDatabaseHandle(null);
        assertThrows(DataAccessException.class, () -> orgPlacementMapper.insert(placement));
    }

    @Test
    void databaseHandleIsUniqueAcrossOrgs() {
        Organization first = newOrg();
        Organization second = newOrg();
        String handle = "cnx_" + unique();
        orgPlacementMapper.insert(silo(first.getId(), handle));
        assertThrows(DataAccessException.class, () -> orgPlacementMapper.insert(silo(second.getId(), handle)));
    }

    @Test
    void effectivePlacementCarriesNullModeForRowlessOrg() {
        Organization org = newOrg();

        OrgPlacement effective = orgPlacementMapper.findEffectiveByOrg(org.getId());

        assertEquals(org.getId(), effective.getOrgId());
        assertNull(effective.getPlacementMode());
        assertNull(effective.getDatabaseHandle());
    }

    @Test
    void effectivePlacementReturnsPersistedRow() {
        Organization org = newOrg();
        String handle = "cnx_" + unique();
        orgPlacementMapper.insert(silo(org.getId(), handle));

        OrgPlacement effective = orgPlacementMapper.findEffectiveByOrg(org.getId());

        assertEquals("connex_operated_silo", effective.getPlacementMode());
        assertEquals(handle, effective.getDatabaseHandle());
    }

    @Test
    void effectivePlacementIsNullForMissingOrg() {
        assertNull(orgPlacementMapper.findEffectiveByOrg(999999999));
    }

    @Test
    void sharedDefaultCarriesPooledPosture() {
        OrgPlacement placement = OrgPlacement.sharedDefault(42);
        assertEquals("shared", placement.getPlacementMode());
        assertEquals("provider_managed", placement.getStorageEncryptionMode());
        assertEquals("connex_cloud_provider", placement.getKeyController());
        assertFalse(placement.isRevocationSupported());
    }

    private OrgPlacement silo(int orgId, String databaseHandle) {
        OrgPlacement placement = OrgPlacement.sharedDefault(orgId);
        placement.setPlacementMode("connex_operated_silo");
        placement.setDatabaseHandle(databaseHandle);
        return placement;
    }

    private Organization newOrg() {
        Organization org = new Organization();
        org.setName("Org " + unique());
        org.setSlug("org-" + unique());
        organizationMapper.insert(org);
        return org;
    }

    @Test
    void distinctDedicatedHandlesListsOnlyDedicatedCatalogs() {
        Organization dedicated = newOrg();
        String handle = "cnx_" + unique();
        OrgPlacement placement = OrgPlacement.sharedDefault(dedicated.getId());
        placement.setPlacementMode("dedicated_database");
        placement.setDatabaseHandle(handle);
        orgPlacementMapper.insert(placement);
        newOrg();

        assertTrue(orgPlacementMapper.distinctDedicatedHandles().contains(handle));
        assertFalse(orgPlacementMapper.distinctDedicatedHandles().stream().anyMatch(Objects::isNull));
    }
}
