package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.brief.DealBriefService;
import ooo.klae.connex.backend.ai.riskrationale.DealRiskRationaleService;
import ooo.klae.connex.backend.dto.BandCounts;
import ooo.klae.connex.backend.dto.CountDto;
import ooo.klae.connex.backend.dto.CompanyEngagementDto;
import ooo.klae.connex.backend.dto.CompanySegmentQueryRequest;
import ooo.klae.connex.backend.dto.DecayCounts;
import ooo.klae.connex.backend.dto.DealAgingDto;
import ooo.klae.connex.backend.dto.DealKpisDto;
import ooo.klae.connex.backend.dto.DealPipelineValueDto;
import ooo.klae.connex.backend.dto.DealPrimaryContactDto;
import ooo.klae.connex.backend.dto.DealRiskAnalyticsDto;
import ooo.klae.connex.backend.dto.DealRevenueSeriesDto;
import ooo.klae.connex.backend.dto.DealStageDistributionDto;
import ooo.klae.connex.backend.dto.DealTopDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.TaskSummaryDto;
import ooo.klae.connex.backend.dto.ScoringIdsRequest;
import ooo.klae.connex.backend.dto.TrendCounts;
import ooo.klae.connex.backend.dto.WarmthSummaryDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.ActivityService;
import ooo.klae.connex.backend.services.BulkOperationService;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.ConnectionService;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.EmploymentService;
import ooo.klae.connex.backend.services.NoteService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.TaskService;
import ooo.klae.connex.backend.services.WorkspaceService;

@ExtendWith(MockitoExtension.class)
class RecordListControllerTest {
    @Mock private PersonService personService;
    @Mock private EmploymentService employmentService;
    @Mock private ConnectionService connectionService;
    @Mock private BulkOperationService bulkOperationService;
    @Mock private CompanyService companyService;
    @Mock private DealService dealService;
    @Mock private DealRiskService dealRiskService;
    @Mock private DealBriefService dealBriefService;
    @Mock private DealRiskRationaleService dealRiskRationaleService;
    @Mock private WorkspaceService workspaceService;
    @Mock private NoteService noteService;
    @Mock private TaskService taskService;
    @Mock private ActivityService activityService;
    @Mock private ScoringService scoringService;

    @Test
    void personsWithoutFilterRequirePageEndpoint() {
        PersonController controller = new PersonController(
            personService, employmentService, connectionService, bulkOperationService);

        assertThrows(BadRequestException.class, () -> controller.getPersons(null, null, null));

        verify(personService, never()).getAllPersons();
    }

    @Test
    void personsPageClampsSize() {
        PersonController controller = new PersonController(
            personService, employmentService, connectionService, bulkOperationService);
        when(personService.getPersonsPage(null, null, null, null, null, false, 100, 0)).thenReturn(List.of());
        when(personService.countPersons(null, null, null, false)).thenReturn(0L);

        var response = controller.getPersonsPage(0, 500, null, null, null, null, null, false);

        assertEquals(0, response.total());
        verify(personService).getPersonsPage(null, null, null, null, null, false, 100, 0);
    }

    @Test
    void personsPageRejectsWarmthSort() {
        PersonController controller = new PersonController(
            personService, employmentService, connectionService, bulkOperationService);

        assertThrows(BadRequestException.class, () -> controller.getPersonsPage(
            1, 25, null, "warmth", "desc", null, null, false));

        verify(personService, never()).getPersonsPage(null, "warmth", "desc", null, null, false, 25, 0);
    }

    @Test
    void personIdsWithoutFilterRequireFilter() {
        PersonController controller = new PersonController(
            personService, employmentService, connectionService, bulkOperationService);

        assertThrows(BadRequestException.class, () -> controller.getPersonIds(null, null, null, false));

        verify(personService, never()).getMatchingPersonIds(null, null, null, false);
    }

    @Test
    void companiesWithoutFilterRequirePageEndpoint() {
        CompanyController controller = new CompanyController(companyService, bulkOperationService);

        assertThrows(BadRequestException.class, () -> controller.getAllCompanies(null));

        verify(companyService, never()).getAllCompanies();
    }

    @Test
    void companiesPageClampsSize() {
        CompanyController controller = new CompanyController(companyService, bulkOperationService);
        when(companyService.getCompaniesPage(null, null, null, null, false, null, 100, 0))
            .thenReturn(List.of());
        when(companyService.countCompanies(null, null, false, null)).thenReturn(0L);

        var response = controller.getCompaniesPage(0, 500, null, null, null, null, false, null);

        assertEquals(0, response.total());
        verify(companyService).getCompaniesPage(null, null, null, null, false, null, 100, 0);
        verify(companyService).countCompanies(null, null, false, null);
    }

    @Test
    void companyEngagementDelegatesTheVisibleCompanyId() {
        CompanyController controller = new CompanyController(companyService, bulkOperationService);
        CompanyEngagementDto engagement = new CompanyEngagementDto(
            List.of(), 0, List.of(), 0, 0, 0, "USD", 0, 0, 0, 0, 0, List.of());
        when(companyService.getCompanyEngagement(17)).thenReturn(engagement);

        assertSame(engagement, controller.getCompanyEngagement(17));

        verify(companyService).getCompanyEngagement(17);
    }

    @Test
    void companyTimelineClampsItsProjectionLimit() {
        CompanyController controller = new CompanyController(companyService, bulkOperationService);
        CompanyService.CompanyTimelineData timeline = new CompanyService.CompanyTimelineData(
            List.of(), List.of(), List.of());
        when(companyService.getCompanyTimeline(17, 100)).thenReturn(timeline);

        var response = controller.getCompanyTimeline(17, 500);

        assertTrue(response.activities().isEmpty());
        assertTrue(response.tasks().isEmpty());
        assertTrue(response.notes().isEmpty());
        verify(companyService).getCompanyTimeline(17, 100);
    }

    @Test
    void companyRelationListsClampTheirProjectionLimits() {
        CompanyController controller = new CompanyController(companyService, bulkOperationService);
        when(companyService.getPersonsByCompanyId(17, 100)).thenReturn(List.of());
        when(companyService.getDealsByCompanyId(17, 100)).thenReturn(List.of());

        assertTrue(controller.getPeopleForCompany(17, 500).isEmpty());
        assertTrue(controller.getDealsForCompany(17, 500).isEmpty());

        verify(companyService).getPersonsByCompanyId(17, 100);
        verify(companyService).getDealsByCompanyId(17, 100);
    }

    @Test
    void companiesPageNormalizesQueryAndForwardsFilters() {
        CompanyController controller = new CompanyController(companyService, bulkOperationService);
        List<String> industries = List.of("Technology", "Finance");
        List<Integer> ids = List.of(3, 5);
        String query = "%50\\%\\_Company%";
        when(companyService.getCompaniesPage(
            query, "industry", "desc", industries, true, ids, 25, 25)).thenReturn(List.of());
        when(companyService.countCompanies(query, industries, true, ids)).thenReturn(7L);

        var response = controller.getCompaniesPage(
            2, 25, "50%_Company", "industry", "desc", industries, true, ids);

        assertEquals(7, response.total());
        verify(companyService).getCompaniesPage(
            query, "industry", "desc", industries, true, ids, 25, 25);
        verify(companyService).countCompanies(query, industries, true, ids);
    }

    @Test
    void companyIdsWithoutFilterRequireFilter() {
        CompanyController controller = new CompanyController(companyService, bulkOperationService);

        assertThrows(BadRequestException.class,
            () -> controller.getCompanyIds(" ", List.of(), false, List.of()));

        verify(companyService, never()).getMatchingCompanyIds(null, List.of(), false, List.of());
    }

    @Test
    void companyIdsAcceptIdsAsTheOnlyFilter() {
        CompanyController controller = new CompanyController(companyService, bulkOperationService);
        List<Integer> ids = List.of(3, 5);
        when(companyService.getMatchingCompanyIds(null, null, false, ids)).thenReturn(ids);

        assertSame(ids, controller.getCompanyIds(null, null, false, ids));

        verify(companyService).getMatchingCompanyIds(null, null, false, ids);
    }

    @Test
    void companySegmentPageNormalizesQueryAndKeepsEvaluatedIdsOffTheRequestUrl() {
        CompanyController controller = new CompanyController(companyService, bulkOperationService);
        CompanySegmentQueryRequest request = new CompanySegmentQueryRequest();
        SegmentDefinition definition = new SegmentDefinition();
        request.setDefinition(definition);
        request.setPage(2);
        request.setSize(25);
        request.setQ("50%_Company");
        request.setSort("name");
        request.setDir("desc");
        request.setIndustry(List.of("Technology"));
        request.setNoIndustry(true);
        when(companyService.getSegmentCompaniesPage(
            definition, "%50\\%\\_Company%", "name", "desc", List.of("Technology"), true, 25, 25))
            .thenReturn(new PageResponse<>(List.of(), 7));

        var response = controller.getSegmentCompaniesPage(request);

        assertEquals(7, response.total());
        verify(companyService).getSegmentCompaniesPage(
            definition, "%50\\%\\_Company%", "name", "desc", List.of("Technology"), true, 25, 25);
    }

    @Test
    void companySegmentIdsDelegateTheDefinitionAndCompanyFilters() {
        CompanyController controller = new CompanyController(companyService, bulkOperationService);
        CompanySegmentQueryRequest request = new CompanySegmentQueryRequest();
        SegmentDefinition definition = new SegmentDefinition();
        List<Integer> ids = List.of(3, 5);
        request.setDefinition(definition);
        request.setQ("Target");
        request.setIndustry(List.of("Technology"));
        when(companyService.getMatchingSegmentCompanyIds(
            definition, "%Target%", List.of("Technology"), false)).thenReturn(ids);

        assertSame(ids, controller.getSegmentCompanyIds(request));

        verify(companyService).getMatchingSegmentCompanyIds(
            definition, "%Target%", List.of("Technology"), false);
    }

    @Test
    void companyFacetsAreAssembledFromServiceValues() {
        CompanyController controller = new CompanyController(companyService, bulkOperationService);
        List<String> industries = List.of("Finance", "Technology");
        when(companyService.distinctIndustries()).thenReturn(industries);
        when(companyService.hasCompanyWithoutIndustry()).thenReturn(true);

        var facets = controller.getCompanyFacets();

        assertSame(industries, facets.industries());
        assertTrue(facets.hasNoIndustry());
    }

    @Test
    void dealsWithoutFilterRequirePageEndpoint() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService);

        assertThrows(BadRequestException.class, () -> controller.getDeals(null, null, null, null, null));

        verify(dealService, never()).getAllDeals();
    }

    @Test
    void dealsPageClampsSize() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService);
        when(dealService.queryDealsPage(
            null, null, null, null, null, null, null, false, null, null, 100, 0))
            .thenReturn(new PageResponse<>(List.of(), 37));

        var response = controller.getDealsPage(
            0, 500, null, null, null, null, null, null, null, false, null, null);

        assertEquals(37, response.total());
        verify(dealService).queryDealsPage(
            null, null, null, null, null, null, null, false, null, null, 100, 0);
    }

    @Test
    void dealsPageRejectsInvalidStatusAndDirection() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService);

        assertThrows(BadRequestException.class, () -> controller.getDealsPage(
            1, 25, null, null, "sideways", null, null, null, null, false, null, null));
        assertThrows(BadRequestException.class, () -> controller.getDealsPage(
            1, 25, null, null, null, null, null, null, null, false, List.of("stale"), null));

        verify(dealService, never()).queryDealsPage(
            null, null, null, null, null, null, null, false, null, null, 25, 0);
    }

    @Test
    void dealsPageExpandsClosedAndBoundsFilterLists() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService);
        when(dealService.queryDealsPage(
            null, null, null, null, List.of(2, 3), null, null, true,
            List.of("open", "won", "lost"), List.of("high", "none"), 25, 0))
            .thenReturn(new PageResponse<>(List.of(), 0));

        controller.getDealsPage(
            1, 25, null, null, null, null, List.of(2, 3, 2), null, null, true,
            List.of("open", "closed"), List.of("high", "none"));

        verify(dealService).queryDealsPage(
            null, null, null, null, List.of(2, 3), null, null, true,
            List.of("open", "won", "lost"), List.of("high", "none"), 25, 0);
        assertThrows(BadRequestException.class, () -> controller.getDealsPage(
            1, 25, null, null, null, null, List.of(0), null, null, false, null, null));
    }

    @Test
    void dealBoardRequiresPositivePipelineAndDelegates() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService);
        when(dealService.getDealBoard(4)).thenReturn(List.of());

        assertTrue(controller.getDealBoard(4).isEmpty());
        assertThrows(BadRequestException.class, () -> controller.getDealBoard(0));

        verify(dealService).getDealBoard(4);
    }

    @Test
    void dealPrimaryContactsNormalizeAndDelegateTheBoundedIdBatch() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService);
        List<DealPrimaryContactDto> contacts = List.of(
            new DealPrimaryContactDto(4, 9, "Primary", null));
        when(dealService.getPrimaryContacts(List.of(4, 2))).thenReturn(contacts);

        assertSame(contacts, controller.getPrimaryContacts(List.of(4, 2, 4)));
        assertTrue(controller.getPrimaryContacts(null).isEmpty());
        assertThrows(BadRequestException.class,
            () -> controller.getPrimaryContacts(List.of(4, 0)));
        assertThrows(BadRequestException.class, () -> controller.getPrimaryContacts(
            java.util.stream.IntStream.rangeClosed(1, 101).boxed().toList()));

        verify(dealService).getPrimaryContacts(List.of(4, 2));
    }

    @Test
    void dealChartEndpointsNormalizeAndForwardCurrency() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService);
        DealRevenueSeriesDto series = new DealRevenueSeriesDto(List.of(), List.of());
        List<DealStageDistributionDto> distribution = List.of(
            new DealStageDistributionDto(1, 2, 3, 4.0, 5, 6.0));
        when(dealService.getRevenueTimeseries("JPY", null)).thenReturn(series);
        when(dealService.getStageDistribution("JPY")).thenReturn(distribution);

        assertSame(series, controller.getRevenueTimeseries("JPY", null));
        assertSame(distribution, controller.getStageDistribution("JPY"));

        controller.getRevenueTimeseries("  ", null);
        controller.getStageDistribution("");

        verify(dealService).getRevenueTimeseries("JPY", null);
        verify(dealService).getStageDistribution("JPY");
        verify(dealService).getRevenueTimeseries(null, null);
        verify(dealService).getStageDistribution(null);
    }

    @Test
    void dealAnalyticsEndpointsNormalizeAndForwardParameters() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService);
        DealKpisDto kpis = new DealKpisDto(
            0.0, null, 0.0, null, 0, 0, 0.0, 0.0, null, null, 0.0, null,
            List.of(), List.of(), List.of(), List.of());
        List<DealPipelineValueDto> pipelineValues = List.of(
            new DealPipelineValueDto(1, 2.0, 3.0, 4));
        List<DealAgingDto> aging = List.of(new DealAgingDto(1, 2, 3, 4, 5));
        DealTopDto top = new DealTopDto(List.of(), List.of());
        when(dealService.getDealKpis("JPY", 30)).thenReturn(kpis);
        when(dealService.getDealPipelineValue("JPY", 365)).thenReturn(pipelineValues);
        when(dealService.getDealAging(null)).thenReturn(aging);
        when(dealService.getTopDeals(null)).thenReturn(top);

        assertSame(kpis, controller.getDealKpis("JPY", "30d"));
        assertSame(pipelineValues, controller.getDealPipelineValue("JPY", "12m"));
        assertSame(aging, controller.getDealAging("  "));
        assertSame(top, controller.getTopDeals(""));

        controller.getDealKpis(" ", null);

        verify(dealService).getDealKpis("JPY", 30);
        verify(dealService).getDealPipelineValue("JPY", 365);
        verify(dealService).getDealAging(null);
        verify(dealService).getTopDeals(null);
        verify(dealService).getDealKpis(null, 90);
    }

    @Test
    void dealAnalyticsEndpointsRejectInvalidRange() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService);

        assertThrows(BadRequestException.class, () -> controller.getDealKpis(null, "7d"));
        assertThrows(BadRequestException.class, () -> controller.getDealPipelineValue(null, "all"));

        verify(dealService, never()).getDealKpis(any(), anyInt());
        verify(dealService, never()).getDealPipelineValue(any(), anyInt());
    }

    @Test
    void dealClosingSoonCountValidatesAndDelegatesDays() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService);
        CountDto count = new CountDto(4);
        when(dealService.getClosingSoonCount(7)).thenReturn(count);

        assertSame(count, controller.getClosingSoonCount(7));
        assertThrows(BadRequestException.class, () -> controller.getClosingSoonCount(0));

        verify(dealService).getClosingSoonCount(7);
        verify(dealService, never()).getClosingSoonCount(0);
    }

    @Test
    void dealClosingSoonListValidatesDaysAndBoundsLimit() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService);
        when(dealService.getClosingSoonDeals(7, 100)).thenReturn(List.of());

        assertTrue(controller.getClosingSoonDeals(7, 500).isEmpty());
        assertThrows(BadRequestException.class, () -> controller.getClosingSoonDeals(0, 6));

        verify(dealService).getClosingSoonDeals(7, 100);
        verify(dealService, never()).getClosingSoonDeals(0, 6);
    }

    @Test
    void interactiveDealRiskRequiresIdsAndAnalyticsUsesBoundedProjection() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService);
        DealRiskAnalyticsDto analytics = new DealRiskAnalyticsDto(List.of(), false);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(dealRiskService.analytics(7)).thenReturn(analytics);

        assertThrows(BadRequestException.class, () -> controller.getDealRisks(null));
        assertSame(analytics, controller.getDealRiskAnalytics());

        verify(dealRiskService, never()).assessWorkspace(anyInt());
        verify(dealRiskService).analytics(7);
    }

    @Test
    void notesWithoutFilterRequirePageEndpoint() {
        NoteController controller = new NoteController(noteService);

        assertThrows(BadRequestException.class, () -> controller.getNotes(null, null, null));

        verify(noteService, never()).getAllNotes();
    }

    @Test
    void notesPageClampsSize() {
        NoteController controller = new NoteController(noteService);
        when(noteService.getNotesPage(100, 0, false)).thenReturn(List.of());
        when(noteService.countNotes(false)).thenReturn(0L);

        var response = controller.getNotesPage(0, 500, false);

        assertEquals(0, response.total());
        verify(noteService).getNotesPage(100, 0, false);
    }

    @Test
    void tasksWithoutFilterRequirePageEndpoint() {
        TaskController controller = new TaskController(taskService);

        assertThrows(BadRequestException.class, () -> controller.getTasks(null, null, null));

        verify(taskService, never()).getAllTasks();
    }

    @Test
    void tasksPageClampsSize() {
        TaskController controller = new TaskController(taskService);
        when(taskService.getTasksPage(100, 0)).thenReturn(List.of());
        when(taskService.countTasks()).thenReturn(0L);

        var response = controller.getTasksPage(0, 500);

        assertEquals(0, response.total());
        verify(taskService).getTasksPage(100, 0);
    }

    @Test
    void taskSummaryDelegatesToService() {
        TaskController controller = new TaskController(taskService);
        TaskSummaryDto summary = new TaskSummaryDto(1, 2, 3, 4, 5);
        when(taskService.getTaskSummary()).thenReturn(summary);

        assertSame(summary, controller.getTaskSummary());
    }

    @Test
    void upcomingTasksAreBoundedBeforeDelegation() {
        TaskController controller = new TaskController(taskService);
        when(taskService.getUpcomingOpenTasks(4)).thenReturn(List.of());

        assertTrue(controller.getUpcomingTasks(4).isEmpty());
        assertThrows(BadRequestException.class, () -> controller.getUpcomingTasks(21));
        verify(taskService).getUpcomingOpenTasks(4);
    }

    @Test
    void activitiesWithoutFilterOrPaginationRequirePageEndpoint() {
        ActivityController controller = new ActivityController(activityService);

        assertThrows(BadRequestException.class, () -> controller.getActivities(null, null, null, null, null));

        verify(activityService, never()).getAllActivities();
    }

    @Test
    void activitiesPageClampsSize() {
        ActivityController controller = new ActivityController(activityService);
        when(activityService.getActivitiesPage(null, null, null, 100, 0)).thenReturn(List.of());
        when(activityService.countActivities(null, null, null)).thenReturn(0L);

        var response = controller.getActivitiesPage(0, 500, null, null, null);

        assertEquals(0, response.total());
        verify(activityService).getActivitiesPage(null, null, null, 100, 0);
    }

    @Test
    void activityAnalyticsRangesAndDaysAreValidatedBeforeDelegation() {
        ActivityController controller = new ActivityController(activityService);
        when(activityService.getActivityVolume(30)).thenReturn(List.of());
        when(activityService.getTeamLeaderboard(365)).thenReturn(List.of());
        when(activityService.getUpcomingCount(7)).thenReturn(new CountDto(2));

        assertTrue(controller.getActivityVolume("30d").isEmpty());
        assertTrue(controller.getTeamLeaderboard("12m").isEmpty());
        assertEquals(2, controller.getUpcomingCount(7).count());
        assertThrows(BadRequestException.class, () -> controller.getActivityVolume("7d"));
        assertThrows(BadRequestException.class, () -> controller.getTeamLeaderboard("all"));
        assertThrows(BadRequestException.class, () -> controller.getUpcomingCount(0));

        verify(activityService).getActivityVolume(30);
        verify(activityService).getTeamLeaderboard(365);
        verify(activityService).getUpcomingCount(7);
    }

    @Test
    void scoringContactsRequireIds() {
        ScoringController controller = new ScoringController(scoringService, workspaceService);

        assertThrows(BadRequestException.class, () -> controller.contacts(null));
    }

    @Test
    void scoringCompaniesRejectTooManyIds() {
        ScoringController controller = new ScoringController(scoringService, workspaceService);
        List<Integer> ids = java.util.stream.IntStream.rangeClosed(1, 101).boxed().toList();

        assertThrows(BadRequestException.class, () -> controller.companies(ids));
    }

    @Test
    void scoringCompanyBatchUsesBodyIdsWithoutQueryStringLimit() {
        ScoringController controller = new ScoringController(scoringService, workspaceService);
        ScoringIdsRequest request = new ScoringIdsRequest();
        request.setIds(List.of(3, 4, 4));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(scoringService.scoreCompanies(
            7, new java.util.LinkedHashSet<>(List.of(3, 4)))).thenReturn(List.of());

        assertTrue(controller.companyBatch(request).isEmpty());
        verify(scoringService).scoreCompanies(7, new java.util.LinkedHashSet<>(List.of(3, 4)));
    }

    @Test
    void mapCompanyScoresUseTheBoundedGetPath() {
        ScoringController controller = new ScoringController(scoringService, workspaceService);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(scoringService.scoreCompaniesForMap(7)).thenReturn(List.of());

        assertTrue(controller.mapCompanies().isEmpty());

        verify(scoringService).scoreCompaniesForMap(7);
    }

    @Test
    void scoringContactsDelegateBoundedIds() {
        ScoringController controller = new ScoringController(scoringService, workspaceService);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(scoringService.scoreContacts(7, new java.util.LinkedHashSet<>(List.of(3, 4)))).thenReturn(List.of());

        List<?> response = controller.contacts(List.of(3, 4));

        assertEquals(0, response.size());
        verify(scoringService).scoreContacts(7, new java.util.LinkedHashSet<>(List.of(3, 4)));
    }

    @Test
    void coolingScoresUseCurrentWorkspaceAndValidateLimit() {
        ScoringController controller = new ScoringController(scoringService, workspaceService);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(scoringService.coolingContacts(7, 6)).thenReturn(List.of());
        when(scoringService.coolingCompanies(7, 8)).thenReturn(List.of());

        assertTrue(controller.coolingContacts(6).isEmpty());
        assertTrue(controller.coolingCompanies(8).isEmpty());
        assertThrows(BadRequestException.class, () -> controller.coolingContacts(0));
        assertThrows(BadRequestException.class, () -> controller.coolingCompanies(101));

        verify(scoringService).coolingContacts(7, 6);
        verify(scoringService).coolingCompanies(7, 8);
    }

    @Test
    void scoringSummaryUsesCurrentWorkspaceAuthorizationPath() {
        ScoringController controller = new ScoringController(scoringService, workspaceService);
        WarmthSummaryDto summary = new WarmthSummaryDto(
            new BandCounts(1, 2, 3, 4),
            new BandCounts(5, 6, 7, 8),
            new TrendCounts(9, 10, 11),
            new DecayCounts(12, 13, 14)
        );
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(scoringService.summarize(7)).thenReturn(summary);

        assertSame(summary, controller.summary());
        verify(scoringService).summarize(7);
    }
}
