package ooo.klae.connex.backend.mappers;

import static ooo.klae.connex.backend.support.OrganizationShareScopes.orgWorkspaceIdsJson;
import static ooo.klae.connex.backend.support.OrganizationShareScopes.workspaceIdsJson;
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
 * is outside the owning organization, inserts nothing — even if every
 * service-layer check were bypassed. Complements the service-level
 * {@code ShareServiceTest.cannotShareAcrossOrganizations} (which proves the
 * request path throws) by proving the data layer refuses on its own.
 *
 * <p>Since #811 the organization ceiling reads the control-derived workspace
 * allowlist rather than joining the control-plane {@code workspace} table, so
 * these tests supply the allowlist ShareService would and additionally forge one
 * to prove the owning end of the ceiling is enforced too.
 *
 * <p>The guarantee these tests pin is therefore narrower than it was: the statement
 * consults no control fact, so it refuses a cross-organization grant for every
 * allowlist DERIVED from a real snapshot — the owner's snapshot omits the foreign
 * target, the target's snapshot omits the owner — but a caller that fabricates an
 * array naming workspaces from both organizations is not refused. That residual is
 * pinned by {@code shareCompany_fabricatedAllowlistSpanningTwoOrganizations_insertsARowTheReadCeilingRefuses}
 * — which also shows the forged row grants no visibility, because the read path
 * carries its own ceiling — and bounded by {@code ShareGrantAllowlistProvenanceArchTest},
 * which keeps the allowlist supplied only by the control-plane snapshot component.
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
            person.getId(), workspace.getId(), sibling.getId(), grantedBy, false,
                orgWorkspaceIdsJson(workspaceMapper, workspace.getId())));
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
            sibling.getId(), newUser().getId(), false,
                orgWorkspaceIdsJson(workspaceMapper, workspace.getId()));

        assertEquals(1, affected);
        assertTrue(companyMapper.exists(sibling.getId(), company.getId()),
            "a same-org grant makes the record visible in the target workspace");
    }

    @Test
    void shareCompany_acrossOrganizations_insertsNothing() {
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Company company = newCompany();

        int affected = shareMapper.shareCompany(company.getId(), workspace.getId(),
            foreign.getId(), newUser().getId(), false,
                orgWorkspaceIdsJson(workspaceMapper, workspace.getId()));

        assertEquals(0, affected, "the SQL org ceiling must refuse a cross-org grant");
        assertFalse(companyMapper.exists(foreign.getId(), company.getId()),
            "a refused grant must leave the record invisible in the foreign workspace");
    }

    @Test
    void sharePerson_acrossOrganizations_insertsNothing() {
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Person person = newPerson(newCompany());

        int affected = shareMapper.sharePerson(person.getId(), workspace.getId(),
            foreign.getId(), newUser().getId(), false,
                orgWorkspaceIdsJson(workspaceMapper, workspace.getId()));

        assertEquals(0, affected);
        assertFalse(personMapper.exists(foreign.getId(), person.getId()));
    }

    @Test
    void sharePipeline_acrossOrganizations_insertsNothing() {
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Pipeline pipeline = newPipeline();

        int affected = shareMapper.sharePipeline(pipeline.getId(), workspace.getId(),
            foreign.getId(), newUser().getId(), false,
                orgWorkspaceIdsJson(workspaceMapper, workspace.getId()));

        assertEquals(0, affected);
        assertFalse(pipelineMapper.pipelineExists(foreign.getId(), pipeline.getId()));
    }

    @Test
    void sharePipeline_sameOrganizationGrantsReadOnlyStageVisibility() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        assertEquals(1, shareMapper.sharePipeline(
            pipeline.getId(), workspace.getId(), sibling.getId(), newUser().getId(), false,
                orgWorkspaceIdsJson(workspaceMapper, workspace.getId())));

        assertNotNull(pipelineMapper.getVisibleStageById(sibling.getId(), stage.getId()));
        assertNull(pipelineMapper.getStageById(sibling.getId(), stage.getId()));
    }

    @Test
    void sharedStageVisibilityRejectsRowsWhoseOwnerDiffersFromTheParentPipeline() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Pipeline pipeline = newPipeline();
        shareMapper.sharePipeline(
            pipeline.getId(), workspace.getId(), sibling.getId(), newUser().getId(), false,
                orgWorkspaceIdsJson(workspaceMapper, workspace.getId()));
        Stage mismatched = new Stage();
        mismatched.setWorkspaceId(sibling.getId());
        mismatched.setName("Mismatched");
        mismatched.setPipeline(pipeline);
        mismatched.setPosition(0);
        pipelineMapper.insertStage(mismatched);

        assertNull(pipelineMapper.getVisibleStageById(sibling.getId(), mismatched.getId()));
        assertTrue(pipelineMapper.getStagesByPipelineId(sibling.getId(), pipeline.getId()).isEmpty());
    }

    /**
     * The organization ceiling is now the control-derived allowlist ShareService loads, so the
     * target end of it has to be enforced on its own: a same-organization target that the
     * snapshot does not list must still insert nothing.
     */
    @Test
    void shareCompany_targetOutsideTheSuppliedAllowlist_insertsNothing() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Company company = newCompany();

        int affected = shareMapper.shareCompany(company.getId(), workspace.getId(),
            sibling.getId(), newUser().getId(), false, workspaceIdsJson(workspace.getId()));

        assertEquals(0, affected,
            "a target absent from the organization allowlist must be refused");
        assertFalse(companyMapper.exists(sibling.getId(), company.getId()));
        assertEquals(1, shareMapper.shareCompany(company.getId(), workspace.getId(),
            sibling.getId(), newUser().getId(), false,
                orgWorkspaceIdsJson(workspaceMapper, workspace.getId())),
            "the same grant succeeds once the real organization allowlist is supplied");
    }

    /**
     * A caller that bypasses ShareService and anchors on the TARGET's organization — the real
     * snapshot of the foreign organization — still inserts nothing: the grant checks the owning
     * workspace against the same allowlist, and that snapshot does not contain it.
     */
    @Test
    void shareCompany_allowlistSnapshotOfTheTargetsOrganization_insertsNothing() {
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Company company = newCompany();

        int affected = shareMapper.shareCompany(company.getId(), workspace.getId(),
            foreign.getId(), newUser().getId(), false,
                orgWorkspaceIdsJson(workspaceMapper, foreign.getId()));

        assertEquals(0, affected,
            "the owning workspace must appear in the allowlist the grant is judged against");
        assertFalse(companyMapper.exists(foreign.getId(), company.getId()));
    }

    /** The same owner-side refusal for contacts and pipelines. */
    @Test
    void sharePersonAndPipeline_allowlistSnapshotOfTheTargetsOrganization_insertNothing() {
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Person person = newPerson(newCompany());
        Pipeline pipeline = newPipeline();
        String forged = orgWorkspaceIdsJson(workspaceMapper, foreign.getId());

        assertEquals(0, shareMapper.sharePerson(person.getId(), workspace.getId(),
            foreign.getId(), newUser().getId(), false, forged));
        assertEquals(0, shareMapper.sharePipeline(pipeline.getId(), workspace.getId(),
            foreign.getId(), newUser().getId(), false, forged));
        assertFalse(personMapper.exists(foreign.getId(), person.getId()));
        assertFalse(pipelineMapper.pipelineExists(foreign.getId(), pipeline.getId()));
    }

    /**
     * Documented residual, not a guard that closes a hole. The grant statement judges both ends
     * against the array it is handed and consults no control fact, so its refusal is only as
     * strong as the array's provenance. Every array derived from a real snapshot refuses this
     * cross-organization grant, whichever end it is anchored on; an array a direct caller
     * FABRICATES from both organizations is not refused, and the row lands.
     *
     * <p>What that row does NOT buy is cross-organization visibility: every share-reading
     * visibility predicate carries its own same-organization ceiling
     * ({@code OrgShareCeilingArchTest.every_share_read_predicate_carries_the_same_org_ceiling}),
     * so the forged row is inert on the read path. The bound on the grant statement's own
     * guarantee is why {@code ShareGrantAllowlistProvenanceArchTest} constrains the supplier; this
     * test exists so nobody reads the tests above as proving more than they do, in either
     * direction.
     */
    @Test
    void shareCompany_fabricatedAllowlistSpanningTwoOrganizations_insertsARowTheReadCeilingRefuses() {
        Workspace foreign = newWorkspaceInOrg(newOrganization().getId());
        Company company = newCompany();
        int grantedBy = newUser().getId();

        assertEquals(0, shareMapper.shareCompany(company.getId(), workspace.getId(),
            foreign.getId(), grantedBy, false,
                orgWorkspaceIdsJson(workspaceMapper, workspace.getId())),
            "the owning organization's snapshot omits the foreign target");
        assertEquals(0, shareMapper.shareCompany(company.getId(), workspace.getId(),
            foreign.getId(), grantedBy, false,
                orgWorkspaceIdsJson(workspaceMapper, foreign.getId())),
            "the target organization's snapshot omits the owning workspace");
        assertEquals(0, shareRowCount(company.getId(), foreign.getId()));

        int fabricated = shareMapper.shareCompany(company.getId(), workspace.getId(),
            foreign.getId(), grantedBy, false,
                workspaceIdsJson(workspace.getId(), foreign.getId()));

        assertEquals(1, fabricated,
            "residual: an allowlist no snapshot could produce is not refused by SQL alone");
        assertEquals(1, shareRowCount(company.getId(), foreign.getId()),
            "the forged grant really does write a cross-organization share row");
        assertFalse(companyMapper.exists(foreign.getId(), company.getId()),
            "the forged row is inert: the read-path organization ceiling refuses it independently, "
                + "so the residual is an unwanted row rather than cross-organization visibility");
    }

    private int shareRowCount(int companyId, int targetWorkspaceId) {
        Integer rows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM company_share WHERE company_id = ? AND workspace_id = ?",
            Integer.class, companyId, targetWorkspaceId);
        return rows == null ? 0 : rows;
    }

    /** An absent or empty allowlist selects no rows, so the grant fails closed. */
    @Test
    void shareCompany_emptyOrNullAllowlist_insertsNothing() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Company company = newCompany();

        assertEquals(0, shareMapper.shareCompany(company.getId(), workspace.getId(),
            sibling.getId(), newUser().getId(), false, workspaceIdsJson()));
        assertEquals(0, shareMapper.shareCompany(company.getId(), workspace.getId(),
            sibling.getId(), newUser().getId(), false, null));
        assertFalse(companyMapper.exists(sibling.getId(), company.getId()));
    }

    @Test
    void shareCompany_notOwnedByActingWorkspace_insertsNothing() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Workspace another = newWorkspaceInOrg(orgIdOf(workspace));
        Company company = newCompany();

        int affected = shareMapper.shareCompany(company.getId(), sibling.getId(),
            another.getId(), newUser().getId(), false, orgWorkspaceIdsJson(workspaceMapper, sibling.getId()));

        assertEquals(0, affected, "only the owning workspace can grant a share");
        assertFalse(companyMapper.exists(another.getId(), company.getId()));
    }

    @Test
    void shareCompany_reGrantWithSameValues_isIdempotentNotAnError() {
        Workspace sibling = newWorkspaceInOrg(orgIdOf(workspace));
        Company company = newCompany();
        int grantedBy = newUser().getId();

        assertEquals(1, shareMapper.shareCompany(company.getId(), workspace.getId(),
            sibling.getId(), grantedBy, false, orgWorkspaceIdsJson(workspaceMapper, workspace.getId())));
        int again = shareMapper.shareCompany(company.getId(), workspace.getId(),
            sibling.getId(), grantedBy, false, orgWorkspaceIdsJson(workspaceMapper, workspace.getId()));

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
            newUser().getId(), false, orgWorkspaceIdsJson(workspaceMapper, workspace.getId()));

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
            newUser().getId(), false, orgWorkspaceIdsJson(workspaceMapper, workspace.getId()));

        List<ShareDto> viaOwner = shareMapper.listCompanyShares(workspace.getId(), company.getId());
        List<ShareDto> viaOther = shareMapper.listCompanyShares(sibling.getId(), company.getId());

        assertEquals(1, viaOwner.size());
        assertEquals(sibling.getId(), viaOwner.getFirst().getWorkspaceId());
        assertNull(viaOwner.getFirst().getWorkspaceName(),
            "the workspace name is control data; ShareService hydrates it from its snapshot");
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
