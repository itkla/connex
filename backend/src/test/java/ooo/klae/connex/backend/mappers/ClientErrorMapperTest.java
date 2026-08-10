package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.ClientErrorSupportRowDto;

class ClientErrorMapperTest extends AbstractMapperTest {
    @Autowired private ClientErrorMapper clientErrorMapper;
    @Autowired private OrganizationMapper organizationMapper;

    @Test
    void organizationSliceJoinsWorkspaceWithoutCrossingIntoAnotherOrganization() {
        Organization firstOrg = newOrganization();
        Organization secondOrg = newOrganization();
        Workspace firstWorkspace = newWorkspace(firstOrg.getId());
        Workspace secondWorkspace = newWorkspace(secondOrg.getId());
        String correlationId = "client-correlation-123";
        clientErrorMapper.insert(
            firstWorkspace.getId(), correlationId, "3819274061", "/records/people/1");
        clientErrorMapper.insert(
            secondWorkspace.getId(), correlationId, "2046813579", "/records/people/2");

        Instant now = Instant.now();
        List<ClientErrorSupportRowDto> rows = clientErrorMapper.findOrgSupportSlice(
            firstOrg.getId(),
            now.minus(1, ChronoUnit.DAYS),
            now.plus(1, ChronoUnit.DAYS),
            correlationId,
            10);

        assertEquals(1, rows.size());
        assertEquals(firstWorkspace.getId(), rows.getFirst().workspaceId());
        assertEquals("3819274061", rows.getFirst().digest());
    }

    private Organization newOrganization() {
        String suffix = unique();
        Organization organization = new Organization();
        organization.setName("Organization " + suffix);
        organization.setSlug("org-" + suffix);
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace newWorkspace(int orgId) {
        String suffix = unique();
        Workspace created = new Workspace();
        created.setName("Workspace " + suffix);
        created.setSlug("workspace-" + suffix);
        created.setOrgId(orgId);
        workspaceMapper.insert(created);
        return created;
    }
}
