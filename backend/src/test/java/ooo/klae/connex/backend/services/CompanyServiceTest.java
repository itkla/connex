package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.CompanyEngagementCountsDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;

class CompanyServiceTest extends AbstractServiceTest {

    @Autowired CompanyService companyService;
    @Autowired ShareMapper shareMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean RuleTriggerPublisher ruleTriggers;
    @MockitoBean NotificationChangePublisher notificationChanges;

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
            List.of(ownerWorkspace.getId(), workspace.getId()));
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
            () -> companyService.deleteCompany(shared.getId()));

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
        assertThrows(ResourceNotFoundException.class, () -> companyService.getPersonsByCompanyId(-1, 100));
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
            () -> companyService.getMatchingCompanyIds(null, null, false, null, MemberScope.allTeam()));
    }

    @Test
    void normalizedNameMatchUsesBoundedVisibleCandidateQuery() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        Company exact = new Company();
        exact.setId(11);
        exact.setName("ANALYTICAL   LABS");
        Company prefixOnly = new Company();
        prefixOnly.setId(12);
        prefixOnly.setName("Analytical Labs Group");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(mapper.findVisibleNameCandidates(
                7, "analytical%labs%", "analytical labs", 17))
            .thenReturn(List.of(exact, prefixOnly));
        CompanyService service = companyService(mapper, workspaceService);

        assertEquals(List.of(exact),
            service.findVisibleByNormalizedName("  Ａnalytical　Labs  ").companies());

        verify(mapper).findVisibleNameCandidates(
            7, "analytical%labs%", "analytical labs", 17);
        verify(mapper, never()).getAllCompanies(7);
    }

    @Test
    void getMatchingCompanyIdsForwardsEveryFilterWithinTheCurrentWorkspace() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        CompanyService service = companyService(mapper, workspaceService);
        List<String> industry = List.of("Technology");
        List<Integer> requestedIds = List.of(3, 5);
        List<Integer> matchingIds = List.of(3);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(mapper.countCompanies(
            7, "%Target%", industry, true, requestedIds, MemberScope.allTeam())).thenReturn(1L);
        when(mapper.getCompanyIdsFiltered(
            7, "%Target%", industry, true, requestedIds, MemberScope.allTeam(), 1000, 0))
            .thenReturn(matchingIds);

        assertEquals(matchingIds,
            service.getMatchingCompanyIds("%Target%", industry, true, requestedIds, MemberScope.allTeam()));

        verify(mapper).countCompanies(
            7, "%Target%", industry, true, requestedIds, MemberScope.allTeam());
        verify(mapper).getCompanyIdsFiltered(
            7, "%Target%", industry, true, requestedIds, MemberScope.allTeam(), 1000, 0);
    }

    @Test
    void getMatchingCompanyIdsRejectsTooManyMatchesBeforeFetchingIds() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        CompanyService service = companyService(mapper, workspaceService);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(mapper.countCompanies(
            7, "%Target%", null, false, null, MemberScope.allTeam())).thenReturn(1001L);

        assertThrows(BadRequestException.class,
            () -> service.getMatchingCompanyIds("%Target%", null, false, null, MemberScope.allTeam()));

        verify(mapper, never()).getCompanyIdsFiltered(
            7, "%Target%", null, false, null, MemberScope.allTeam(), 1000, 0);
    }

    @Test
    void companyEngagementUsesOnlyBoundedProjectionsAndAggregates() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        PersonMapper personMapper = mock(PersonMapper.class);
        DealMapper dealMapper = mock(DealMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(mapper.exists(7, 9)).thenReturn(true);
        when(mapper.getCompanyEngagementCounts(7, 9))
            .thenReturn(new CompanyEngagementCountsDto(12, 4, 3, 1, 2, 1));
        when(mapper.getCompanyEngagementUsers(7, 9, 5)).thenReturn(List.of());
        when(mapper.getCompanyRevenueByCurrency(7, 9)).thenReturn(List.of());
        when(mapper.getCompanyEngagementWeeks(
            org.mockito.ArgumentMatchers.eq(7), org.mockito.ArgumentMatchers.eq(9),
            anyString(), anyString())).thenReturn(List.of());
        when(personMapper.getCompanyEngagementPeople(7, 9, 5)).thenReturn(List.of());
        CompanyService service = new CompanyService(
            mapper, mock(TagMapper.class), personMapper, dealMapper,
            mock(ActivityMapper.class), mock(NoteMapper.class), mock(TaskMapper.class),
            mock(AuthService.class),
            mock(AuditService.class),
            mock(ooo.klae.connex.backend.notifications.NotificationChangePublisher.class),
            mock(RuleTriggerPublisher.class), workspaceService, mock(CustomFieldValueService.class),
            mock(SegmentService.class), mock(ReferenceService.class),
            Clock.fixed(Instant.parse("2026-07-11T00:00:00Z"), ZoneOffset.UTC),
            mock(ooo.klae.connex.backend.storage.ManagedObjectService.class));

        var engagement = service.getCompanyEngagement(9);

        assertEquals(12, engagement.personCount());
        assertEquals(12, engagement.weeklyEngagement().size());
        verify(personMapper).getCompanyEngagementPeople(7, 9, 5);
        verify(personMapper, never()).getPersonsByCompanyId(7, 9, null);
        verify(dealMapper, never()).getDealsByCompanyIdPage(7, 9, 5);
    }

    @Test
    void companyTimelineUsesBoundedCompanyScopedQueriesAndVisibleNotes() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        PersonMapper personMapper = mock(PersonMapper.class);
        DealMapper dealMapper = mock(DealMapper.class);
        ActivityMapper activityMapper = mock(ActivityMapper.class);
        NoteMapper noteMapper = mock(NoteMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        ReferenceService referenceService = mock(ReferenceService.class);
        Activity activity = new Activity();
        Task task = new Task();
        Note note = new Note();
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(11);
        when(mapper.exists(7, 9)).thenReturn(true);
        when(activityMapper.getCompanyActivities(7, 9, 25)).thenReturn(List.of(activity));
        when(taskMapper.getCompanyTasks(7, 9, 25)).thenReturn(List.of(task));
        when(noteMapper.getVisibleCompanyNotes(7, 9, 11, 25)).thenReturn(List.of(note));
        when(referenceService.hydrateActivities(7, List.of(activity))).thenReturn(List.of(activity));
        when(referenceService.hydrateTasks(7, List.of(task))).thenReturn(List.of(task));
        when(referenceService.hydrate(7, List.of(note))).thenReturn(List.of(note));
        CompanyService service = new CompanyService(
            mapper, mock(TagMapper.class), personMapper, dealMapper,
            activityMapper, noteMapper, taskMapper, mock(AuthService.class), mock(AuditService.class),
            mock(ooo.klae.connex.backend.notifications.NotificationChangePublisher.class),
            mock(RuleTriggerPublisher.class), workspaceService, mock(CustomFieldValueService.class),
            mock(SegmentService.class), referenceService, Clock.systemUTC(),
            mock(ooo.klae.connex.backend.storage.ManagedObjectService.class));

        CompanyService.CompanyTimelineData timeline = service.getCompanyTimeline(9, 25);

        assertEquals(List.of(activity), timeline.activities());
        assertEquals(List.of(task), timeline.tasks());
        assertEquals(List.of(note), timeline.notes());
        verify(activityMapper).getCompanyActivities(7, 9, 25);
        verify(taskMapper).getCompanyTasks(7, 9, 25);
        verify(noteMapper).getVisibleCompanyNotes(7, 9, 11, 25);
        verify(referenceService).hydrateActivities(7, List.of(activity));
    }

    private CompanyService companyService(CompanyMapper mapper, WorkspaceService workspaceService) {
        return new CompanyService(
            mapper,
            mock(TagMapper.class),
            mock(PersonMapper.class),
            mock(DealMapper.class),
            mock(ActivityMapper.class),
            mock(NoteMapper.class),
            mock(TaskMapper.class),
            mock(AuthService.class),
            mock(AuditService.class),
            mock(ooo.klae.connex.backend.notifications.NotificationChangePublisher.class),
            mock(RuleTriggerPublisher.class),
            workspaceService,
            mock(CustomFieldValueService.class),
            mock(SegmentService.class),
            mock(ReferenceService.class),
            Clock.systemUTC(),
            mock(ooo.klae.connex.backend.storage.ManagedObjectService.class)
        );
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
