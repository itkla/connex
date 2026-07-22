package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.ShareDto;

/**
 * SQL-level proof of the share invariants (#97, #313 Phase 2): a share grant
 * whose record is not owned by the acting workspace, or whose target workspace
 * belongs to a different organization, inserts nothing — even if every
 * service-layer check were bypassed. Complements the service-level
 * {@code ShareServiceTest.cannotShareAcrossOrganizations} (which proves the
 * request path throws) by proving the data layer refuses on its own.
 */
class ShareMapperTest extends AbstractMapperTest {

    @Autowired private ShareMapper shareMapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void visiblePersonLockPreservesSameOrgShareCeilingAndRestrictionFields() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Person person = newPerson(newCompany());
        Person unshared = new Person();
        unshared.setWorkspaceId(sibling.getId());
        unshared.setName("Unshared contact");
        unshared.setEmail("unshared-" + person.getId() + "@example.com");
        personMapper.insert(unshared);
        int grantedBy = newUser().getId();
        assertEquals(1, shareMapper.sharePerson(
            person.getId(), workspace.getId(), sibling.getId(), grantedBy, false));
        personMapper.updateProcessingRestrictions(workspace.getId(), person.getId(), true, true);
        jdbcTemplate.update(
            "INSERT INTO person_share (person_id, workspace_id, granted_by, can_edit) VALUES (?, ?, ?, ?)",
            person.getId(), foreign.getId(), grantedBy, false);

        Person owned = personMapper.getVisiblePersonByIdForUpdate(workspace.getId(), person.getId());
        Person shared = personMapper.getVisiblePersonByIdForUpdate(sibling.getId(), person.getId());

        assertNotNull(owned);
        assertNotNull(owned.getSuspendedAt());
        assertNotNull(owned.getProvisionCeasedAt());
        assertNotNull(shared);
        assertNotNull(shared.getSuspendedAt());
        assertNotNull(shared.getProvisionCeasedAt());
        assertNull(personMapper.getVisiblePersonByIdForUpdate(workspace.getId(), unshared.getId()));
        assertNull(personMapper.getVisiblePersonByIdForUpdate(foreign.getId(), person.getId()));
        assertNull(personMapper.getVisiblePersonByIdForUpdate(workspace.getId(), Integer.MAX_VALUE));
    }

    @Test
    void shareCompany_sameOrganization_grantsVisibility() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Company company = newCompany();

        int affected = shareMapper.shareCompany(company.getId(), workspace.getId(),
            sibling.getId(), newUser().getId(), false);

        assertEquals(1, affected);
        assertTrue(companyMapper.exists(sibling.getId(), company.getId()),
            "a same-org grant makes the record visible in the target workspace");
    }

    @Test
    void shareCompany_acrossOrganizations_insertsNothing() {
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Company company = newCompany();

        int affected = shareMapper.shareCompany(company.getId(), workspace.getId(),
            foreign.getId(), newUser().getId(), false);

        assertEquals(0, affected, "the SQL org ceiling must refuse a cross-org grant");
        assertFalse(companyMapper.exists(foreign.getId(), company.getId()),
            "a refused grant must leave the record invisible in the foreign workspace");
    }

    @Test
    void sharePerson_acrossOrganizations_insertsNothing() {
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Person person = newPerson(newCompany());

        int affected = shareMapper.sharePerson(person.getId(), workspace.getId(),
            foreign.getId(), newUser().getId(), false);

        assertEquals(0, affected);
        assertFalse(personMapper.exists(foreign.getId(), person.getId()));
    }

    @Test
    void sharePipeline_acrossOrganizations_insertsNothing() {
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Pipeline pipeline = newPipeline();

        int affected = shareMapper.sharePipeline(pipeline.getId(), workspace.getId(),
            foreign.getId(), newUser().getId(), false);

        assertEquals(0, affected);
        assertFalse(pipelineMapper.pipelineExists(foreign.getId(), pipeline.getId()));
    }

    @Test
    void sharePipeline_sameOrganizationGrantsReadOnlyStageVisibility() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        assertEquals(1, shareMapper.sharePipeline(
            pipeline.getId(), workspace.getId(), sibling.getId(), newUser().getId(), false));

        assertNotNull(pipelineMapper.getVisibleStageById(sibling.getId(), stage.getId()));
        assertNull(pipelineMapper.getStageById(sibling.getId(), stage.getId()));
    }

    @Test
    void sharedStageVisibilityRejectsRowsWhoseOwnerDiffersFromTheParentPipeline() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Pipeline pipeline = newPipeline();
        shareMapper.sharePipeline(
            pipeline.getId(), workspace.getId(), sibling.getId(), newUser().getId(), false);
        Stage mismatched = new Stage();
        mismatched.setWorkspaceId(sibling.getId());
        mismatched.setName("Mismatched");
        mismatched.setPipeline(pipeline);
        mismatched.setPosition(0);
        pipelineMapper.insertStage(mismatched);

        assertNull(pipelineMapper.getVisibleStageById(sibling.getId(), mismatched.getId()));
        assertTrue(pipelineMapper.getStagesByPipelineId(sibling.getId(), pipeline.getId()).isEmpty());
    }

    @Test
    void shareCompany_notOwnedByActingWorkspace_insertsNothing() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Workspace another = newWorkspaceInOrg(orgIdOf(workspace));
        Company company = newCompany();

        int affected = shareMapper.shareCompany(company.getId(), sibling.getId(),
            another.getId(), newUser().getId(), false);

        assertEquals(0, affected, "only the owning workspace can grant a share");
        assertFalse(companyMapper.exists(another.getId(), company.getId()));
    }

    @Test
    void shareCompany_reGrantWithSameValues_isIdempotentNotAnError() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Company company = newCompany();
        int grantedBy = newUser().getId();

        assertEquals(1, shareMapper.shareCompany(company.getId(), workspace.getId(),
            sibling.getId(), grantedBy, false));
        int again = shareMapper.shareCompany(company.getId(), workspace.getId(),
            sibling.getId(), grantedBy, false);

        assertEquals(1, again,
            "under the driver's found-rows semantics an unchanged re-grant still reports the matched "
                + "row; ShareService additionally probes existence so the refusal contract survives "
                + "an affected-rows driver setting");
        assertTrue(companyMapper.exists(sibling.getId(), company.getId()),
            "the grant survives the idempotent re-grant");
    }

    @Test
    void unshareCompany_viaNonOwningWorkspace_deletesNothing() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Company company = newCompany();
        shareMapper.shareCompany(company.getId(), workspace.getId(), sibling.getId(),
            newUser().getId(), false);

        int affected = shareMapper.unshareCompany(company.getId(), sibling.getId(), sibling.getId());

        assertEquals(0, affected, "revocation must anchor on the owning workspace");
        assertTrue(companyMapper.exists(sibling.getId(), company.getId()),
            "the grant survives a revocation attempted through a non-owning workspace");
        assertEquals(1, shareMapper.unshareCompany(company.getId(), workspace.getId(), sibling.getId()));
    }

    @Test
    void listCompanyShares_anchoredToOwningWorkspace() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Company company = newCompany();
        shareMapper.shareCompany(company.getId(), workspace.getId(), sibling.getId(),
            newUser().getId(), false);

        List<ShareDto> viaOwner = shareMapper.listCompanyShares(workspace.getId(), company.getId());
        List<ShareDto> viaOther = shareMapper.listCompanyShares(sibling.getId(), company.getId());

        assertEquals(1, viaOwner.size());
        assertEquals(sibling.getId(), viaOwner.getFirst().getWorkspaceId());
        assertTrue(viaOther.isEmpty(),
            "share listings are only readable through the workspace that owns the record");
    }

    private Organization newOrganization() {
        String s = unique();
        Organization organization = new Organization();
        organization.setName("Org " + s);
        organization.setSlug("org-" + s);
        organizationMapper.insert(organization);
        return organization;
    }

    private Workspace newWorkspaceInOrg(int orgId) {
        String s = unique();
        Workspace ws = new Workspace();
        ws.setName("Workspace " + s);
        ws.setSlug("ws-" + s);
        ws.setOrgId(orgId);
        workspaceMapper.insert(ws);
        return ws;
    }

    private int orgIdOf(Workspace ws) {
        Integer orgId = workspaceMapper.getOrgId(ws.getId());
        assertTrue(orgId != null, "test workspace must belong to an organization");
        return orgId;
    }
}
