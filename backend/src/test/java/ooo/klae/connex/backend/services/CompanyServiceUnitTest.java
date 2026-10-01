package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import org.apache.hc.client5.http.psl.PublicSuffixMatcherLoader;
import org.junit.jupiter.api.Test;

import com.google.i18n.phonenumbers.PhoneNumberUtil;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.CompanyEngagementCountsDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;

class CompanyServiceUnitTest {

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
            7, "%Target%", industry, true, requestedIds, MemberScope.allTeam(), false, null))
            .thenReturn(1L);
        when(mapper.getCompanyIdsFiltered(
            7, "%Target%", industry, true, requestedIds, MemberScope.allTeam(), false, null, 1000, 0))
            .thenReturn(matchingIds);

        assertEquals(matchingIds, service.getMatchingCompanyIds(
            "%Target%", industry, true, requestedIds, MemberScope.allTeam(), false, null));

        verify(mapper).countCompanies(
            7, "%Target%", industry, true, requestedIds, MemberScope.allTeam(), false, null);
        verify(mapper).getCompanyIdsFiltered(
            7, "%Target%", industry, true, requestedIds, MemberScope.allTeam(), false, null, 1000, 0);
    }

    @Test
    void getMatchingCompanyIdsRejectsTooManyMatchesBeforeFetchingIds() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        CompanyService service = companyService(mapper, workspaceService);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(mapper.countCompanies(
            7, "%Target%", null, false, null, MemberScope.allTeam(), false, null)).thenReturn(1001L);

        assertThrows(BadRequestException.class, () -> service.getMatchingCompanyIds(
            "%Target%", null, false, null, MemberScope.allTeam(), false, null));

        verify(mapper, never()).getCompanyIdsFiltered(
            7, "%Target%", null, false, null, MemberScope.allTeam(), false, null, 1000, 0);
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
            mock(ooo.klae.connex.backend.storage.ManagedObjectService.class),
            mock(IdentityIntakeService.class),
            mock(MatchingService.class),
            mock(DuplicatePreflightService.class),
            mock(DuplicateDecisionLockService.class),
            mock(RecordCreationAugmentationService.class));

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
            mock(ooo.klae.connex.backend.storage.ManagedObjectService.class),
            mock(IdentityIntakeService.class),
            mock(MatchingService.class),
            mock(DuplicatePreflightService.class),
            mock(DuplicateDecisionLockService.class),
            mock(RecordCreationAugmentationService.class));

        CompanyService.CompanyTimelineData timeline = service.getCompanyTimeline(9, 25);

        assertEquals(List.of(activity), timeline.activities());
        assertEquals(List.of(task), timeline.tasks());
        assertEquals(List.of(note), timeline.notes());
        verify(activityMapper).getCompanyActivities(7, 9, 25);
        verify(taskMapper).getCompanyTasks(7, 9, 25);
        verify(noteMapper).getVisibleCompanyNotes(7, 9, 11, 25);
        verify(referenceService).hydrateActivities(7, List.of(activity));
    }

    /**
     * The warmth band facet projects {@code w.*} columns that only exist when the aggregate join was
     * emitted, so a missing filter must fail explicitly rather than as an unresolved-column 500.
     */
    @Test
    void theWarmthFacetRefusesAMissingFilterInsteadOfReachingTheMapper() {
        CompanyMapper mapper = mock(CompanyMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        CompanyService service = companyService(mapper, workspaceService);

        assertThrows(NullPointerException.class, () -> service.countsByWarmthBand(null));

        verify(mapper, never()).countsByWarmthBand(anyInt(), any());
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
            mock(ooo.klae.connex.backend.storage.ManagedObjectService.class),
            mock(IdentityIntakeService.class),
            matchingService(),
            mock(DuplicatePreflightService.class),
            mock(DuplicateDecisionLockService.class),
            mock(RecordCreationAugmentationService.class)
        );
    }

    private static MatchingService matchingService() {
        return new MatchingService(
            PhoneNumberUtil.getInstance(),
            PublicSuffixMatcherLoader.getDefault());
    }

}
