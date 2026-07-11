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
import ooo.klae.connex.backend.dto.DecayCounts;
import ooo.klae.connex.backend.dto.DealAgingDto;
import ooo.klae.connex.backend.dto.DealKpisDto;
import ooo.klae.connex.backend.dto.DealPipelineValueDto;
import ooo.klae.connex.backend.dto.DealRevenueSeriesDto;
import ooo.klae.connex.backend.dto.DealStageDistributionDto;
import ooo.klae.connex.backend.dto.DealTopDto;
import ooo.klae.connex.backend.dto.TaskSummaryDto;
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
        when(dealService.getDealsPage(
            null, null, null, null, null, null, null, null, 100, 0)).thenReturn(List.of());
        when(dealService.countDeals(null, null, null, null, null, null)).thenReturn(37L);

        var response = controller.getDealsPage(
            0, 500, null, null, null, null, null, null, null, null);

        assertEquals(37, response.total());
        verify(dealService).getDealsPage(
            null, null, null, null, null, null, null, null, 100, 0);
        verify(dealService).countDeals(null, null, null, null, null, null);
    }

    @Test
    void dealsPageRejectsInvalidStatusAndDirection() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService);

        assertThrows(BadRequestException.class, () -> controller.getDealsPage(
            1, 25, null, null, "sideways", null, null, null, null, null));
        assertThrows(BadRequestException.class, () -> controller.getDealsPage(
            1, 25, null, null, null, null, null, null, null, "stale"));

        verify(dealService, never()).getDealsPage(
            null, null, null, null, null, null, null, null, 25, 0);
    }

    @Test
    void dealChartEndpointsNormalizeAndForwardCurrency() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService);
        DealRevenueSeriesDto series = new DealRevenueSeriesDto(List.of(), List.of());
        List<DealStageDistributionDto> distribution = List.of(
            new DealStageDistributionDto(1, 2, 3, 4.0, 5, 6.0));
        when(dealService.getRevenueTimeseries("JPY")).thenReturn(series);
        when(dealService.getStageDistribution("JPY")).thenReturn(distribution);

        assertSame(series, controller.getRevenueTimeseries("JPY"));
        assertSame(distribution, controller.getStageDistribution("JPY"));

        controller.getRevenueTimeseries("  ");
        controller.getStageDistribution("");

        verify(dealService).getRevenueTimeseries("JPY");
        verify(dealService).getStageDistribution("JPY");
        verify(dealService).getRevenueTimeseries(null);
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
    void notesWithoutFilterRequirePageEndpoint() {
        NoteController controller = new NoteController(noteService);

        assertThrows(BadRequestException.class, () -> controller.getNotes(null, null, null));

        verify(noteService, never()).getAllNotes();
    }

    @Test
    void notesPageClampsSize() {
        NoteController controller = new NoteController(noteService);
        when(noteService.getNotesPage(100, 0)).thenReturn(List.of());
        when(noteService.countNotes()).thenReturn(0L);

        var response = controller.getNotesPage(0, 500);

        assertEquals(0, response.total());
        verify(noteService).getNotesPage(100, 0);
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
    void scoringContactsDelegateBoundedIds() {
        ScoringController controller = new ScoringController(scoringService, workspaceService);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(scoringService.scoreContacts(7, new java.util.LinkedHashSet<>(List.of(3, 4)))).thenReturn(List.of());

        List<?> response = controller.contacts(List.of(3, 4));

        assertEquals(0, response.size());
        verify(scoringService).scoreContacts(7, new java.util.LinkedHashSet<>(List.of(3, 4)));
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
