package ooo.klae.connex.backend.services;

import static ooo.klae.connex.backend.support.OrganizationShareScopes.orgWorkspaceIdsJson;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;


import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;

class CompanyServiceTest extends AbstractServiceTest {

    @Autowired CompanyService companyService;
    @Autowired ShareMapper shareMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean RuleTriggerPublisher ruleTriggers;
    @MockitoBean NotificationChangePublisher notificationChanges;

    @Test
    void removeTagIsIdempotentAndUnauditedWhenTagNoLongerExists() {
        Company company = newCompany();
        int auditBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ?",
            Integer.class,
            workspace.getId());

        assertDoesNotThrow(
            () -> companyService.removeTag(company.getId(), Integer.MAX_VALUE));

        assertEquals(auditBefore, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ?",
            Integer.class,
            workspace.getId()));
    }

    @Test
    void conditionalTagRemovalRefusesOnceTheAssociationChanged() {
        Company company = newCompany();
        Tag tag = newTag();
        companyService.addTag(company.getId(), tag.getId());

        assertDoesNotThrow(() -> companyService.removeTagIfUnchanged(company.getId(), tag.getId()));
        assertThrows(
            ConflictException.class,
            () -> companyService.removeTagIfUnchanged(company.getId(), tag.getId()));
    }

    @Test
    void removeTagReportsWhetherItRemovedTheAssociationAndAuditsOnlyARemoval() {
        Company company = newCompany();
        Tag tag = newTag();
        companyService.addTag(company.getId(), tag.getId());
        int auditBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND action = 'company.removeTag'",
            Integer.class,
            workspace.getId());

        assertTrue(companyService.removeTag(company.getId(), tag.getId()));
        assertFalse(companyService.removeTag(company.getId(), tag.getId()));

        assertEquals(auditBefore + 1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND action = 'company.removeTag'",
            Integer.class,
            workspace.getId()));
    }

    @Test
    void createAndUpdateReconcileCurrentIdentityHistory() {
        Company draft = new Company();
        draft.setName("Identity company");
        draft.setWebsite("https://www.identity-" + unique() + ".co.jp/about");
        draft.setPhone("090-1234-5678");

        Company created = companyService.createCompany(draft);

        assertEquals(
            List.of("domain", "phone"),
            jdbcTemplate.queryForList(
                """
                SELECT kind
                FROM company_identity
                WHERE workspace_id = ? AND company_id = ?
                ORDER BY kind
                """,
                String.class,
                workspace.getId(),
                created.getId()));
        assertEquals(
            2,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM company_identity
                WHERE workspace_id = ? AND company_id = ?
                  AND source_system = 'interactive_create'
                  AND purpose_of_use_code IS NULL
                  AND superseded_at IS NULL
                """,
                Integer.class,
                workspace.getId(),
                created.getId()));

        Company update = new Company();
        update.setName(created.getName());
        String updatedDomain = "updated-" + unique() + ".co.jp";
        update.setWebsite("https://" + updatedDomain);
        update.setPhone("invalid phone");
        companyService.updateCompany(created.getId(), update);
        companyService.updateCompany(created.getId(), update);

        assertEquals(
            3,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM company_identity WHERE workspace_id = ? AND company_id = ?",
                Integer.class,
                workspace.getId(),
                created.getId()));
        assertEquals(
            List.of(updatedDomain),
            jdbcTemplate.queryForList(
                """
                SELECT normalized_value
                FROM company_identity
                WHERE workspace_id = ? AND company_id = ? AND superseded_at IS NULL
                """,
                String.class,
                workspace.getId(),
                created.getId()));
        assertEquals(
            2,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM company_identity
                WHERE workspace_id = ? AND company_id = ? AND superseded_at IS NOT NULL
                """,
                Integer.class,
                workspace.getId(),
                created.getId()));
        assertEquals(
            "interactive_update",
            jdbcTemplate.queryForObject(
                """
                SELECT source_system
                FROM company_identity
                WHERE workspace_id = ? AND company_id = ? AND normalized_value = ?
                """,
                String.class,
                workspace.getId(),
                created.getId(),
            updatedDomain));
    }

    @Test
    void livePhoneChangesRefreshCompanyCollisionMembership() {
        String sharedPhone = "090-6789-0123";
        Company first = new Company();
        first.setName("First collision company");
        first.setWebsite("https://first-" + unique() + ".co.jp");
        first.setPhone(sharedPhone);
        companyService.createCompany(first);
        Company second = new Company();
        second.setName("Second collision company");
        second.setWebsite("https://second-" + unique() + ".co.jp");
        second.setPhone(sharedPhone);
        Company createdSecond = companyService.createCompany(second);

        assertEquals(
            2,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM identity_collision ic
                JOIN company_identity ci
                  ON ci.workspace_id = ic.workspace_id
                  AND ci.id = ic.company_identity_id
                WHERE ic.workspace_id = ?
                  AND ci.kind = 'phone'
                  AND ci.normalized_value = '+819067890123'
                """,
                Integer.class,
                workspace.getId()));

        second.setPhone("090-7890-1234");
        companyService.updateCompany(createdSecond.getId(), second);

        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM identity_collision WHERE workspace_id = ?",
                Integer.class,
                workspace.getId()));

        second.setPhone(sharedPhone);
        companyService.updateCompany(createdSecond.getId(), second);

        assertEquals(
            2,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM identity_collision WHERE workspace_id = ?",
                Integer.class,
                workspace.getId()));
    }

    @Test
    void websiteUniquenessPreservesLegacyAndCanonicalEquivalence() {
        Company noncanonical = new Company();
        noncanonical.setName("Noncanonical website");
        noncanonical.setWebsite("https://github.io/");
        Company created = companyService.createCompany(noncanonical);
        Company equivalentUpdate = new Company();
        equivalentUpdate.setName(created.getName());
        equivalentUpdate.setWebsite("https://www.github.io");

        companyService.updateCompany(created.getId(), equivalentUpdate);

        Company legacyDuplicate = new Company();
        legacyDuplicate.setName("Legacy duplicate");
        legacyDuplicate.setWebsite("https://github.io");
        assertThrows(
            DuplicateResourceException.class,
            () -> companyService.createCompany(legacyDuplicate));

        String canonicalDomain = unique() + ".co.jp";
        Company canonical = new Company();
        canonical.setName("Canonical website");
        canonical.setWebsite("https://sales." + canonicalDomain + "/path");
        companyService.createCompany(canonical);
        Company canonicalDuplicate = new Company();
        canonicalDuplicate.setName("Canonical duplicate");
        canonicalDuplicate.setWebsite("https://www." + canonicalDomain + "/other");

        assertThrows(
            DuplicateResourceException.class,
            () -> companyService.createCompany(canonicalDuplicate));
    }

    @Test
    void createRejectsClientSuppliedLogoUrl() {
        Company company = new Company();
        company.setName("No remote logo");
        company.setLogoUrl("https://attacker.example/logo.png");

        Company created = companyService.createCompany(company);

        assertNull(created.getLogoUrl());
        assertEquals(currentUser.getId(), created.getOwnerId());
        assertNull(companyMapper.getCompanyById(workspace.getId(), created.getId()).getLogoUrl());
        assertEquals(currentUser.getId(),
            companyMapper.getCompanyById(workspace.getId(), created.getId()).getOwnerId());
    }

    @Test
    void genericUpdatePreservesAndReturnsCurrentManagedLogo() {
        Company company = newCompany();
        User owner = newUser();
        companyMapper.updateOwner(workspace.getId(), company.getId(), owner.getId());
        String managed = "/api/companies/" + company.getId()
            + "/logo/550e8400-e29b-41d4-a716-446655440000.png";
        assertEquals(1, companyMapper.updateLogoUrlIfCurrent(
            workspace.getId(), company.getId(), null, managed));
        company.setName("Renamed company");
        company.setLogoUrl("https://attacker.example/logo.png");

        Company updated = companyService.updateCompany(company.getId(), company);

        assertEquals(managed, updated.getLogoUrl());
        assertEquals(owner.getId(), updated.getOwnerId());
        assertEquals(managed,
            companyMapper.getCompanyById(workspace.getId(), company.getId()).getLogoUrl());
    }

    @Test
    void updateOwnerAssignsAndUnassignsMemberWithAuditNotificationAndRuleTrigger() {
        Company company = newCompany();
        User owner = newUser();

        Company assigned = companyService.updateOwner(company.getId(), owner.getId());

        assertEquals(owner.getId(), assigned.getOwnerId());
        String changes = jdbcTemplate.queryForObject(
            "SELECT changes FROM audit_log WHERE workspace_id = ? AND entity_type = 'company' "
                + "AND entity_id = ? AND action = 'company.updateOwner' ORDER BY id DESC LIMIT 1",
            String.class, workspace.getId(), company.getId());
        assertNotNull(changes);
        assertTrue(changes.contains("ownerId"));
        assertTrue(changes.contains(Integer.toString(owner.getId())));
        verify(notificationChanges).publish(workspace.getId(), "company", company.getId());
        verify(ruleTriggers).publish(
            workspace.getId(), "company", company.getId(), "company.owner_changed");

        Company unassigned = companyService.updateOwner(company.getId(), null);

        assertNull(unassigned.getOwnerId());
    }

    @Test
    void updateOwnerRejectsNonMemberBeforeChangingTheCompany() {
        Company company = newCompany();
        User outsider = newUser();
        workspaceMapper.removeMember(workspace.getId(), outsider.getId());

        assertThrows(ooo.klae.connex.backend.exceptions.ForbiddenException.class,
            () -> companyService.updateOwner(company.getId(), outsider.getId()));
        assertNull(companyMapper.getCompanyById(workspace.getId(), company.getId()).getOwnerId());
    }

    @Test
    void coreMutationsRejectSharedInCompanyBeforeSideEffects() {
        Workspace ownerWorkspace = newWorkspaceInSameOrg();
        Company shared = companyInWorkspace(ownerWorkspace);
        shareMapper.shareCompany(
            shared.getId(), ownerWorkspace.getId(), workspace.getId(), currentUser.getId(), true,
                orgWorkspaceIdsJson(workspaceMapper, ownerWorkspace.getId()));
        Tag tag = newTag();
        int auditBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND entity_type = 'company' AND entity_id = ?",
            Integer.class, workspace.getId(), shared.getId());
        Company update = new Company();
        update.setName("Rejected update");

        assertThrows(ResourceNotFoundException.class,
            () -> companyService.updateCompany(shared.getId(), update));
        assertThrows(ResourceNotFoundException.class,
            () -> companyService.addTag(shared.getId(), tag.getId()));
        assertThrows(ResourceNotFoundException.class,
            () -> companyService.removeTag(shared.getId(), tag.getId()));
        assertThrows(ResourceNotFoundException.class,
            () -> companyService.replaceTags(shared.getId(), List.of(tag.getId())));
        assertThrows(ResourceNotFoundException.class,
            () -> companyService.updateOwner(shared.getId(), currentUser.getId()));
        assertThrows(ResourceNotFoundException.class,
            () -> companyService.archiveCompany(shared.getId()));

        assertTrue(companyMapper.existsOwned(ownerWorkspace.getId(), shared.getId()));
        assertEquals(auditBefore, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND entity_type = 'company' AND entity_id = ?",
            Integer.class, workspace.getId(), shared.getId()));
    }

    @Test
    void getPersonsByCompanyId_returnsOnlyMatchingPeople() {
        Company company1 = newCompany();
        Company company2 = newCompany();
        Person p1 = newPerson(company1);
        Person p2 = newPerson(company2);

        List<Person> people = companyService.getPersonsByCompanyId(company1.getId(), 100);

        assertTrue(people.stream().anyMatch(x -> x.getId() == p1.getId()));
        assertTrue(people.stream().noneMatch(x -> x.getId() == p2.getId()));
    }

    @Test
    void getPersonsByCompanyId_throwsWhenCompanyMissing() {
        ResourceNotFoundException failure = assertThrows(
            ResourceNotFoundException.class,
            () -> companyService.getPersonsByCompanyId(-1, 100));

        assertEquals("Company not found", failure.getMessage());
    }

    @Test
    void getDealsByCompanyId_returnsOnlyMatchingDeals() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company1 = newCompany();
        Company company2 = newCompany();
        Deal d1 = newDeal(pipeline, stage, company1);
        Deal d2 = newDeal(pipeline, stage, company2);

        List<Deal> deals = companyService.getDealsByCompanyId(company1.getId(), 100);

        assertTrue(deals.stream().anyMatch(x -> x.getId() == d1.getId()));
        assertTrue(deals.stream().noneMatch(x -> x.getId() == d2.getId()));
    }

    @Test
    void getDealsByCompanyId_throwsWhenCompanyMissing() {
        assertThrows(ResourceNotFoundException.class, () -> companyService.getDealsByCompanyId(-1, 100));
    }

    @Test
    void getMatchingCompanyIdsRejectsRequestsWithoutFilters() {
        assertThrows(BadRequestException.class,
            () -> companyService.getMatchingCompanyIds(
                null, null, false, null, MemberScope.allTeam(), false, null));
    }

    private Workspace newWorkspaceInSameOrg() {
        Workspace other = new Workspace();
        other.setName("Other Workspace");
        other.setSlug("other-" + unique());
        other.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(other);
        return other;
    }

    private Company companyInWorkspace(Workspace target) {
        Company company = new Company();
        company.setName("Company " + unique());
        company.setWorkspaceId(target.getId());
        companyMapper.insert(company);
        return company;
    }
}
