package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.OrgPlacement;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.OrgPlacementMapper;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PlacementRegistryDatabaseConsistencyTest {

    @Autowired private OrgPlacementMapper orgPlacementMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private TenantRoutingProperties routingProperties;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void independentInstancesObserveCommittedRegistryStateWithoutLocalInvalidation() {
        Organization organization = new Organization();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        organization.setName("Placement consistency " + suffix);
        organization.setSlug("placement-consistency-" + suffix);
        organizationMapper.insert(organization);
        try {
            PlacementRegistry first = new PlacementRegistry(orgPlacementMapper, routingProperties);
            PlacementRegistry second = new PlacementRegistry(orgPlacementMapper, routingProperties);

            assertEquals("shared", first.effectivePlacementFor(organization.getId()).getPlacementMode());
            assertEquals("shared", second.effectivePlacementFor(organization.getId()).getPlacementMode());

            OrgPlacement dedicated = OrgPlacement.sharedDefault(organization.getId());
            dedicated.setPlacementMode("dedicated_database");
            dedicated.setDatabaseHandle("cnx_" + suffix);
            orgPlacementMapper.insert(dedicated);

            assertEquals("dedicated_database", first.effectivePlacementFor(organization.getId()).getPlacementMode());
            assertEquals("dedicated_database", second.effectivePlacementFor(organization.getId()).getPlacementMode());

            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());

            assertNull(first.effectivePlacementFor(organization.getId()));
            assertNull(second.effectivePlacementFor(organization.getId()));
        } finally {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }
}
