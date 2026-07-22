package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.ShareDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShareServiceTest extends AbstractServiceTest {

    @Autowired ShareService shareService;
    @Autowired WorkspaceService workspaceService;
    @Autowired TenantContext tenantContext;
    @Autowired OrganizationMapper organizationMapper;
    @Autowired ShareMapper shareMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    private final List<Integer> createdUserIds = new ArrayList<>();
    private final List<Integer> createdOrganizationIds = new ArrayList<>();
    private final List<Integer> createdWorkspaceIds = new ArrayList<>();
    private final List<Integer> createdCompanyIds = new ArrayList<>();
    private final List<Integer> createdPersonIds = new ArrayList<>();

    @AfterEach
    void clearContext() {
        clearRequestContext();
    }

    @AfterEach
    void cleanUpCommittedFixtures() {
        createdPersonIds.forEach(
            id -> jdbcTemplate.update("DELETE FROM person_share WHERE person_id = ?", id));
        createdCompanyIds.forEach(
            id -> jdbcTemplate.update("DELETE FROM company_share WHERE company_id = ?", id));
        createdPersonIds.forEach(id -> jdbcTemplate.update("DELETE FROM person WHERE id = ?", id));
        createdCompanyIds.forEach(id -> jdbcTemplate.update("DELETE FROM company WHERE id = ?", id));
        createdWorkspaceIds.forEach(
            id -> jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", id));
        createdWorkspaceIds.forEach(id -> jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", id));
        createdOrganizationIds.forEach(
            id -> jdbcTemplate.update("DELETE FROM org_member WHERE org_id = ?", id));
        createdOrganizationIds.forEach(
            id -> jdbcTemplate.update("DELETE FROM organization WHERE id = ?", id));
        createdUserIds.forEach(
            id -> jdbcTemplate.update("DELETE FROM workspace_member WHERE user_id = ?", id));
        createdUserIds.forEach(id -> jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", id));
    }

    @Override
    protected User newUser() {
        User user = super.newUser();
        createdUserIds.add(user.getId());
        return user;
    }

    private Company companyIn(int workspaceId) {
        Company company = new Company();
        company.setName("Acme " + unique());
        company.setWorkspaceId(workspaceId);
        companyMapper.insert(company);
        createdCompanyIds.add(company.getId());
        return company;
    }

    private WorkspaceMembershipDto createOwnerWorkspace(String name) {
        WorkspaceMembershipDto created = workspaceService.createWorkspace(name, currentUser.getId());
        createdWorkspaceIds.add(created.getId());
        createdOrganizationIds.add(created.getOrgId());
        return created;
    }

    /**
     * Creates a second workspace inside {@code first}'s organization the way a real
     * owner does: from an administrative tenant context (the placement rule only
     * reuses the active org for owner/admin creators).
     */
    private WorkspaceMembershipDto createSiblingWorkspace(WorkspaceMembershipDto first, String name) {
        tenantContext.set(first.getId(), workspaceService.getOrgId(first.getId()), currentUser.getId(), "owner", null);
        WorkspaceMembershipDto sibling = workspaceService.createWorkspace(name, currentUser.getId());
        createdWorkspaceIds.add(sibling.getId());
        authenticateAs(currentUser, first.getId());
        return sibling;
    }

    @Test
    void sharingMakesACompanyVisibleToTheGrantee() {
        WorkspaceMembershipDto a = createOwnerWorkspace("Owner WS");
        WorkspaceMembershipDto b = createSiblingWorkspace(a, "Grantee WS");
        Company company = companyIn(a.getId());

        assertNull(companyMapper.getCompanyById(b.getId(), company.getId()));
        assertFalse(companyMapper.exists(b.getId(), company.getId()));

        authenticateAs(currentUser, a.getId());
        shareService.share("company", company.getId(), b.getId(), false);
        authenticateAs(currentUser, b.getId());

        Company seenByB = companyMapper.getCompanyById(b.getId(), company.getId());
        assertNotNull(seenByB);
        assertTrue(companyMapper.exists(b.getId(), company.getId()));
        assertEquals(a.getId(), seenByB.getWorkspaceId());
    }

    @Test
    void unshareRemovesVisibility() {
        WorkspaceMembershipDto a = createOwnerWorkspace("Owner2 WS");
        WorkspaceMembershipDto b = createSiblingWorkspace(a, "Grantee2 WS");
        Company company = companyIn(a.getId());

        authenticateAs(currentUser, a.getId());
        shareService.share("company", company.getId(), b.getId(), false);
        assertNotNull(companyMapper.getCompanyById(b.getId(), company.getId()));

        shareService.unshare("company", company.getId(), b.getId());
        authenticateAs(currentUser, b.getId());

        assertNull(companyMapper.getCompanyById(b.getId(), company.getId()));
    }

    @Test
    void listHydratesSortsAndOmitsTargetsMissingFromTheControlSnapshot() {
        WorkspaceMembershipDto owner = createOwnerWorkspace("List Owner WS");
        WorkspaceMembershipDto zulu = createSiblingWorkspace(owner, "Zulu Target WS");
        WorkspaceMembershipDto alpha = createSiblingWorkspace(owner, "Alpha Target WS");
        Company company = companyIn(owner.getId());

        shareService.share("company", company.getId(), zulu.getId(), false);
        shareService.share("company", company.getId(), alpha.getId(), true);

        List<ShareDto> shares = shareService.listShares("company", company.getId());
        assertEquals(List.of("Alpha Target WS", "Zulu Target WS"), shares.stream()
            .map(ShareDto::getWorkspaceName)
            .toList());
        assertTrue(shares.getFirst().isCanEdit());

        jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", alpha.getId());
        jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", alpha.getId());

        shares = shareService.listShares("company", company.getId());
        assertEquals(List.of(zulu.getId()), shares.stream().map(ShareDto::getWorkspaceId).toList());
    }

    @Test
    void shareRunsOutsideAnAmbientCallerTransaction() {
        WorkspaceMembershipDto owner = createOwnerWorkspace("Transaction Owner WS");
        WorkspaceMembershipDto target = createSiblingWorkspace(owner, "Transaction Target WS");
        Company company = companyIn(owner.getId());
        TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);

        callerTransaction.executeWithoutResult(status -> {
            shareService.share("company", company.getId(), target.getId(), false);
            status.setRollbackOnly();
        });

        assertTrue(companyMapper.exists(target.getId(), company.getId()));
    }

    @Test
    void cannotShareToAWorkspaceYouDoNotBelongTo() {
        WorkspaceMembershipDto a = createOwnerWorkspace("Owner3 WS");
        Company company = companyIn(a.getId());

        Workspace foreign = new Workspace();
        foreign.setName("Foreign WS");
        foreign.setSlug("foreign-" + unique());
        workspaceMapper.insert(foreign);
        createdWorkspaceIds.add(foreign.getId());
        User outsider = newUser();
        workspaceMapper.addMember(foreign.getId(), outsider.getId(), "owner");

        tenantContext.set(a.getId(), workspaceService.getOrgId(a.getId()), currentUser.getId(), "owner", null);
        assertThrows(ForbiddenException.class,
            () -> shareService.share("company", company.getId(), foreign.getId(), false));
    }

    @Test
    void cannotShareAcrossOrganizations() {
        WorkspaceMembershipDto a = createOwnerWorkspace("Org1 WS");
        Company company = companyIn(a.getId());

        Organization otherOrg = new Organization();
        otherOrg.setName("Other Org");
        otherOrg.setSlug("other-org-" + unique());
        organizationMapper.insert(otherOrg);
        createdOrganizationIds.add(otherOrg.getId());
        Workspace otherOrgWs = new Workspace();
        otherOrgWs.setOrgId(otherOrg.getId());
        otherOrgWs.setName("Other Org WS");
        otherOrgWs.setSlug("other-org-ws-" + unique());
        workspaceMapper.insert(otherOrgWs);
        createdWorkspaceIds.add(otherOrgWs.getId());
        workspaceMapper.addMember(otherOrgWs.getId(), currentUser.getId(), "owner");

        tenantContext.set(a.getId(), workspaceService.getOrgId(a.getId()), currentUser.getId(), "owner", null);
        assertThrows(ForbiddenException.class,
            () -> shareService.share("company", company.getId(), otherOrgWs.getId(), false));
    }

    @Test
    void provisionCeasedPersonBlocksNewShareButStillAllowsUnshare() {
        WorkspaceMembershipDto owner = createOwnerWorkspace("Person Owner WS");
        WorkspaceMembershipDto existingTarget = createSiblingWorkspace(owner, "Existing Target WS");
        Person person = new Person();
        person.setWorkspaceId(owner.getId());
        person.setName("Provision ceased " + unique());
        personMapper.insert(person);
        createdPersonIds.add(person.getId());

        shareService.share("person", person.getId(), existingTarget.getId(), false);
        WorkspaceMembershipDto blockedTarget = createSiblingWorkspace(owner, "Blocked Target WS");
        personMapper.updateProcessingRestrictions(owner.getId(), person.getId(), false, true);

        assertEquals(0, shareMapper.sharePerson(
            person.getId(), owner.getId(), blockedTarget.getId(), currentUser.getId(), false,
            List.of(owner.getId(), blockedTarget.getId())));
        BadRequestException blocked = assertThrows(BadRequestException.class,
            () -> shareService.share("person", person.getId(), blockedTarget.getId(), false));
        assertEquals("Third-party provision has been ceased for this contact", blocked.getMessage());

        shareService.unshare("person", person.getId(), existingTarget.getId());
        assertTrue(shareService.listShares("person", person.getId()).isEmpty());
    }
}
