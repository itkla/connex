package ooo.klae.connex.backend.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.BandCounts;
import ooo.klae.connex.backend.dto.BulkOperationResult;
import ooo.klae.connex.backend.dto.BulkOwnerRequest;
import ooo.klae.connex.backend.dto.CountDto;
import ooo.klae.connex.backend.dto.CompanyEngagementDto;
import ooo.klae.connex.backend.dto.CompanyOwnerDto;
import ooo.klae.connex.backend.dto.CompanySegmentQueryRequest;
import ooo.klae.connex.backend.dto.DecayCounts;
import ooo.klae.connex.backend.dto.DealAgingDto;
import ooo.klae.connex.backend.dto.DealKpisDto;
import ooo.klae.connex.backend.dto.DealMetricsDto;
import ooo.klae.connex.backend.dto.DealPipelineValueDto;
import ooo.klae.connex.backend.dto.DealPrimaryContactDto;
import ooo.klae.connex.backend.dto.DealRiskAnalyticsDto;
import ooo.klae.connex.backend.dto.DealRevenuePeriodSeriesDto;
import ooo.klae.connex.backend.dto.DealSegmentQueryRequest;
import ooo.klae.connex.backend.dto.DealRevenueSeriesDto;
import ooo.klae.connex.backend.dto.DealStageDistributionDto;
import ooo.klae.connex.backend.dto.DealTopDto;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.PersonOwnerDto;
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
import ooo.klae.connex.backend.services.MemberScopeResolver;
import ooo.klae.connex.backend.services.NoteService;
import ooo.klae.connex.backend.services.PersonService;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.TaskService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.util.AnalyticsPeriods.Window;

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
    @Mock private MemberScopeResolver memberScopeResolver;
    @Mock private NoteService noteService;
    @Mock private TaskService taskService;
    @Mock private ActivityService activityService;
    @Mock private ScoringService scoringService;

    @Test
    void personsWithoutFilterRequirePageEndpoint() {
        PersonController controller = personController();

        assertThrows(BadRequestException.class, () -> controller.getPersons(null, null, null));

        verify(personService, never()).getAllPersons();
    }

    @Test
    void personsPageClampsSize() {
        PersonController controller = personController();
        MemberScope memberScope = MemberScope.fromRequest(null, null, 7);
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(memberScopeResolver.resolve(null, null, 7)).thenReturn(memberScope);
        when(personService.getPersonsPage(
            null, null, null, null, null, false, memberScope, false, 100, 0)).thenReturn(List.of());
        when(personService.countPersons(null, null, null, false, memberScope, false)).thenReturn(0L);

        var response = controller.getPersonsPage(
            0, 500, null, null, null, null, null, false, null, null, false);

        assertEquals(0, response.total());
        verify(personService).getPersonsPage(
            null, null, null, null, null, false, memberScope, false, 100, 0);
        verify(personService).countPersons(null, null, null, false, memberScope, false);
    }

    @Test
    void personsPageRejectsWarmthSort() {
        PersonController controller = personController();

        assertThrows(BadRequestException.class, () -> controller.getPersonsPage(
            1, 25, null, "warmth", "desc", null, null, false, null, null, false));

        verify(personService, never()).getPersonsPage(
            null, "warmth", "desc", null, null, false, null, false, 25, 0);
    }

    @Test
    void personsPageResolvesAndForwardsMemberScopeToPageAndCount() {
        PersonController controller = personController();
        MemberScope memberScope = MemberScope.fromRequest("members", List.of(3, 5), 7);
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(memberScopeResolver.resolve("members", List.of(3, 5), 7)).thenReturn(memberScope);
        when(personService.getPersonsPage(
            "%Target%", "name", "desc", List.of("Acme"), List.of("Director"), true,
            memberScope, false, 25, 25)).thenReturn(List.of());
        when(personService.countPersons(
            "%Target%", List.of("Acme"), List.of("Director"), true, memberScope, false)).thenReturn(4L);

        var response = controller.getPersonsPage(
            2, 25, "Target", "name", "desc", List.of("Acme"), List.of("Director"), true,
            "members", List.of(3, 5), false);

        assertEquals(4, response.total());
        verify(personService).getPersonsPage(
            "%Target%", "name", "desc", List.of("Acme"), List.of("Director"), true,
            memberScope, false, 25, 25);
        verify(personService).countPersons(
            "%Target%", List.of("Acme"), List.of("Director"), true, memberScope, false);
    }

    @Test
    void personIdsWithoutFilterRequireFilter() {
        PersonController controller = personController();
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(memberScopeResolver.resolve(null, null, 7)).thenReturn(MemberScope.allTeam());

        assertThrows(BadRequestException.class,
            () -> controller.getPersonIds(null, null, null, false, null, null, false));

        verify(personService, never()).getMatchingPersonIds(null, null, null, false, MemberScope.allTeam(), false);
    }

    @Test
    void companiesWithoutFilterRequirePageEndpoint() {
        CompanyController controller = companyController();

        assertThrows(BadRequestException.class, () -> controller.getAllCompanies(null));

        verify(companyService, never()).getAllCompanies();
    }

    @Test
    void companiesPageClampsSize() {
        CompanyController controller = companyController();
        MemberScope memberScope = MemberScope.fromRequest(null, null, 7);
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(memberScopeResolver.resolve(null, null, 7)).thenReturn(memberScope);
        when(companyService.getCompaniesPage(
            null, null, null, null, false, null, memberScope, false, 100, 0))
            .thenReturn(List.of());
        when(companyService.countCompanies(null, null, false, null, memberScope, false)).thenReturn(0L);

        var response = controller.getCompaniesPage(
            0, 500, null, null, null, null, false, null, null, null, false);

        assertEquals(0, response.total());
        verify(companyService).getCompaniesPage(
            null, null, null, null, false, null, memberScope, false, 100, 0);
        verify(companyService).countCompanies(null, null, false, null, memberScope, false);
    }

    @Test
    void companyEngagementDelegatesTheVisibleCompanyId() {
        CompanyController controller = companyController();
        CompanyEngagementDto engagement = new CompanyEngagementDto(
            List.of(), 0, List.of(), 0, 0, 0, "USD", 0, 0, 0, 0, 0, List.of());
        when(companyService.getCompanyEngagement(17)).thenReturn(engagement);

        assertSame(engagement, controller.getCompanyEngagement(17));

        verify(companyService).getCompanyEngagement(17);
    }

    @Test
    void companyTimelineClampsItsProjectionLimit() {
        CompanyController controller = companyController();
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
        CompanyController controller = companyController();
        when(companyService.getPersonsByCompanyId(17, 100)).thenReturn(List.of());
        when(companyService.getDealsByCompanyId(17, 100)).thenReturn(List.of());

        assertTrue(controller.getPeopleForCompany(17, 500).isEmpty());
        assertTrue(controller.getDealsForCompany(17, 500).isEmpty());

        verify(companyService).getPersonsByCompanyId(17, 100);
        verify(companyService).getDealsByCompanyId(17, 100);
    }

    @Test
    void companiesPageNormalizesQueryAndForwardsFilters() {
        CompanyController controller = companyController();
        List<String> industries = List.of("Technology", "Finance");
        List<Integer> ids = List.of(3, 5);
        String query = "%50\\%\\_Company%";
        MemberScope memberScope = MemberScope.fromRequest("members", List.of(3, 5), 7);
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(memberScopeResolver.resolve("members", List.of(3, 5), 7)).thenReturn(memberScope);
        when(companyService.getCompaniesPage(
            query, "industry", "desc", industries, true, ids, memberScope, false, 25, 25))
            .thenReturn(List.of());
        when(companyService.countCompanies(query, industries, true, ids, memberScope, false)).thenReturn(7L);

        var response = controller.getCompaniesPage(
            2, 25, "50%_Company", "industry", "desc", industries, true, ids,
            "members", List.of(3, 5), false);

        assertEquals(7, response.total());
        verify(companyService).getCompaniesPage(
            query, "industry", "desc", industries, true, ids, memberScope, false, 25, 25);
        verify(companyService).countCompanies(query, industries, true, ids, memberScope, false);
    }

    @Test
    void companyIdsWithoutFilterRequireFilter() {
        CompanyController controller = companyController();
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(memberScopeResolver.resolve(null, null, 7)).thenReturn(MemberScope.allTeam());

        assertThrows(BadRequestException.class,
            () -> controller.getCompanyIds(" ", List.of(), false, List.of(), null, null, false));

        verify(companyService, never()).getMatchingCompanyIds(
            null, List.of(), false, List.of(), MemberScope.allTeam(), false);
    }

    @Test
    void companyIdsAcceptIdsAsTheOnlyFilter() {
        CompanyController controller = companyController();
        List<Integer> ids = List.of(3, 5);
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(memberScopeResolver.resolve(null, null, 7)).thenReturn(MemberScope.allTeam());
        when(companyService.getMatchingCompanyIds(null, null, false, ids, MemberScope.allTeam(), false))
            .thenReturn(ids);

        assertSame(ids, controller.getCompanyIds(null, null, false, ids, null, null, false));

        verify(companyService).getMatchingCompanyIds(null, null, false, ids, MemberScope.allTeam(), false);
    }

    @Test
    void companySegmentPageNormalizesQueryAndKeepsEvaluatedIdsOffTheRequestUrl() {
        CompanyController controller = companyController();
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
        CompanyController controller = companyController();
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
        CompanyController controller = companyController();
        List<String> industries = List.of("Finance", "Technology");
        List<FacetCount> owners = List.of(new FacetCount("7", 3, null));
        when(companyService.distinctIndustries()).thenReturn(industries);
        when(companyService.hasCompanyWithoutIndustry()).thenReturn(true);
        when(companyService.countsByOwner()).thenReturn(owners);

        var facets = controller.getCompanyFacets();

        assertSame(industries, facets.industries());
        assertTrue(facets.hasNoIndustry());
        assertSame(owners, facets.owners());
    }

    @Test
    void personFacetsAreAssembledFromServiceValues() {
        PersonController controller = personController();
        List<String> companies = List.of("Acme");
        List<String> titles = List.of("Director");
        List<FacetCount> owners = List.of(new FacetCount("7", 2, null));
        when(personService.distinctCompanies()).thenReturn(companies);
        when(personService.distinctTitles()).thenReturn(titles);
        when(personService.hasPersonWithoutCompany()).thenReturn(true);
        when(personService.countsByOwner()).thenReturn(owners);

        var facets = controller.getPersonFacets();

        assertSame(companies, facets.companies());
        assertSame(titles, facets.titles());
        assertTrue(facets.hasNoCompany());
        assertSame(owners, facets.owners());
    }

    @Test
    void companyOwnerEndpointsDelegateSingleAndBulkAssignments() {
        CompanyController controller = companyController();
        Company company = new Company();
        company.setId(17);
        company.setName("Acme");
        company.setOwnerId(9);
        CompanyOwnerDto owner = new CompanyOwnerDto();
        owner.setOwnerId(9);
        when(companyService.updateOwner(17, 9)).thenReturn(company);
        BulkOwnerRequest bulk = new BulkOwnerRequest();
        bulk.setIds(List.of(17, 19));
        bulk.setOwnerId(9);
        BulkOperationResult result = new BulkOperationResult(2, 0, List.of());
        when(bulkOperationService.assignOwnerToCompanies(List.of(17, 19), 9)).thenReturn(result);

        assertEquals(9, controller.updateOwner(17, owner).getOwnerId());
        assertSame(result, controller.bulkAssignOwner(bulk));
    }

    @Test
    void personOwnerEndpointsDelegateSingleAndBulkAssignments() {
        PersonController controller = personController();
        Person person = new Person();
        person.setId(23);
        person.setName("Ada");
        person.setOwnerId(11);
        PersonOwnerDto owner = new PersonOwnerDto();
        owner.setOwnerId(11);
        when(personService.updateOwner(23, 11)).thenReturn(person);
        BulkOwnerRequest bulk = new BulkOwnerRequest();
        bulk.setIds(List.of(23, 29));
        bulk.setOwnerId(11);
        BulkOperationResult result = new BulkOperationResult(2, 0, List.of());
        when(bulkOperationService.assignOwnerToPersons(List.of(23, 29), 11)).thenReturn(result);

        assertEquals(11, controller.updateOwner(23, owner).getOwnerId());
        assertSame(result, controller.bulkAssignOwner(bulk));
    }

    @Test
    void dealsWithoutFilterRequirePageEndpoint() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService, memberScopeResolver);

        assertThrows(BadRequestException.class, () -> controller.getDeals(null, null, null, null, null));

        verify(dealService, never()).getAllDeals();
    }

    @Test
    void dealsPageClampsSize() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService, memberScopeResolver);
        MemberScope memberScope = MemberScope.fromRequest(null, null, 7);
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(memberScopeResolver.resolve(null, null, 7)).thenReturn(memberScope);
        when(dealService.queryDealsPage(
            null, null, null, null, null, null, null, false, null, null,
            memberScope, 100, 0))
            .thenReturn(new PageResponse<>(List.of(), 37));

        var response = controller.getDealsPage(
            0, 500, null, null, null, null, null, null, null, false, null, null,
            null, null);

        assertEquals(37, response.total());
        verify(dealService).queryDealsPage(
            null, null, null, null, null, null, null, false, null, null,
            memberScope, 100, 0);
    }

    @Test
    void dealsPageRejectsInvalidStatusAndDirection() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService, memberScopeResolver);

        assertThrows(BadRequestException.class, () -> controller.getDealsPage(
            1, 25, null, null, "sideways", null, null, null, null, false, null, null,
            null, null));
        assertThrows(BadRequestException.class, () -> controller.getDealsPage(
            1, 25, null, null, null, null, null, null, null, false, List.of("stale"), null,
            null, null));

        verify(dealService, never()).queryDealsPage(
            null, null, null, null, null, null, null, false, null, null,
            null, 25, 0);
    }

    @Test
    void dealsPageExpandsClosedAndBoundsFilterLists() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService, memberScopeResolver);
        MemberScope memberScope = MemberScope.fromRequest(null, null, 7);
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(memberScopeResolver.resolve(null, null, 7)).thenReturn(memberScope);
        when(dealService.queryDealsPage(
            null, null, null, null, List.of(2, 3), null, null, true,
            List.of("open", "won", "lost"), List.of("high", "none"), memberScope, 25, 0))
            .thenReturn(new PageResponse<>(List.of(), 0));

        controller.getDealsPage(
            1, 25, null, null, null, null, List.of(2, 3, 2), null, null, true,
            List.of("open", "closed"), List.of("high", "none"), null, null);

        verify(dealService).queryDealsPage(
            null, null, null, null, List.of(2, 3), null, null, true,
            List.of("open", "won", "lost"), List.of("high", "none"), memberScope, 25, 0);
        assertThrows(BadRequestException.class, () -> controller.getDealsPage(
            1, 25, null, null, null, null, List.of(0), null, null, false, null, null,
            null, null));
    }

    @Test
    void dealSegmentEndpointsPreserveAndNormalizeTheCompleteListScope() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService, memberScopeResolver);
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of());
        DealSegmentQueryRequest request = new DealSegmentQueryRequest();
        request.setPage(0);
        request.setSize(500);
        request.setQ("Acme");
        request.setSort("value");
        request.setDir("desc");
        request.setCurrency("JPY");
        request.setPipelineId(List.of(2, 3, 2));
        request.setCompanyId(List.of(5, 5));
        request.setNoCompany(true);
        request.setStatus(List.of("closed", "open", "closed"));
        request.setRisk(List.of("high", "none", "high"));
        request.setScope("members");
        request.setMemberIds(List.of(8, 9));
        request.setDefinition(definition);
        MemberScope memberScope = MemberScope.fromRequest("members", List.of(8, 9), 7);
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(memberScopeResolver.resolve("members", List.of(8, 9), 7)).thenReturn(memberScope);
        when(dealService.querySegmentDealsPage(
            definition, "%Acme%", "value", "desc", "JPY", List.of(2, 3), null,
            List.of(5), true, List.of("won", "lost", "open"), List.of("high", "none"),
            memberScope, 100, 0)).thenReturn(new PageResponse<>(List.of(), 9));
        when(dealService.querySegmentDealMetrics(
            definition, "%Acme%", "JPY", List.of(2, 3), null, List.of(5), true,
            List.of("won", "lost", "open"), List.of("high", "none"), memberScope))
            .thenReturn(new DealMetricsDto(List.of(), 0));
        when(dealService.getMatchingSegmentDealIds(
            definition, "%Acme%", "JPY", List.of(2, 3), null, List.of(5), true,
            List.of("won", "lost", "open"), List.of("high", "none"), memberScope))
            .thenReturn(List.of(11));

        assertEquals(9, controller.getSegmentDealsPage(request).total());
        assertEquals(0, controller.getSegmentDealMetrics(request).totalCount());
        assertEquals(List.of(11), controller.getSegmentDealIds(request));

        verify(dealService).querySegmentDealsPage(
            definition, "%Acme%", "value", "desc", "JPY", List.of(2, 3), null,
            List.of(5), true, List.of("won", "lost", "open"), List.of("high", "none"),
            memberScope, 100, 0);
        verify(dealService).querySegmentDealMetrics(
            definition, "%Acme%", "JPY", List.of(2, 3), null, List.of(5), true,
            List.of("won", "lost", "open"), List.of("high", "none"), memberScope);
        verify(dealService).getMatchingSegmentDealIds(
            definition, "%Acme%", "JPY", List.of(2, 3), null, List.of(5), true,
            List.of("won", "lost", "open"), List.of("high", "none"), memberScope);
    }

    @Test
    void dealBoardRequiresPositivePipelineAndDelegates() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService, memberScopeResolver);
        when(dealService.getDealBoard(4)).thenReturn(List.of());

        assertTrue(controller.getDealBoard(4).isEmpty());
        assertThrows(BadRequestException.class, () -> controller.getDealBoard(0));

        verify(dealService).getDealBoard(4);
    }

    @Test
    void dealPrimaryContactsNormalizeAndDelegateTheBoundedIdBatch() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService, memberScopeResolver);
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
    void dealChartEndpointsUseServerOwnedTimezoneAndNormalizeCurrency() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService, memberScopeResolver);
        MemberScope allTeam = MemberScope.allTeam();
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(workspaceService.getCurrentAnalyticsTimezone()).thenReturn("Pacific/Honolulu");
        when(memberScopeResolver.resolve(null, null, 7)).thenReturn(allTeam);
        DealRevenueSeriesDto series = new DealRevenueSeriesDto(List.of(), List.of());
        List<DealStageDistributionDto> distribution = List.of(
            new DealStageDistributionDto(1, 2, 3, 4.0, 5, 6.0));
        when(dealService.getRevenueTimeseries("JPY", "Pacific/Honolulu", allTeam)).thenReturn(series);
        when(dealService.getStageDistribution("JPY", allTeam)).thenReturn(distribution);

        assertSame(series, controller.getRevenueTimeseries(
            "JPY", "Mars/Olympus", "25:00", null, null));
        assertSame(distribution, controller.getStageDistribution("JPY", null, null));

        controller.getRevenueTimeseries("  ", null, null, null, null);
        controller.getStageDistribution("", null, null);

        verify(dealService).getRevenueTimeseries("JPY", "Pacific/Honolulu", allTeam);
        verify(dealService).getStageDistribution("JPY", allTeam);
        verify(dealService).getRevenueTimeseries(null, "Pacific/Honolulu", allTeam);
        verify(dealService).getStageDistribution(null, allTeam);
    }

    @Test
    void dealAnalyticsEndpointsNormalizeAndForwardParameters() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService, memberScopeResolver);
        MemberScope allTeam = MemberScope.allTeam();
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(memberScopeResolver.resolve(null, null, 7)).thenReturn(allTeam);
        DealKpisDto kpis = new DealKpisDto(
            0.0, null, 0.0, null, 0, 0, 0.0, 0.0, null, null, 0.0, null,
            List.of(), List.of(), List.of(), List.of());
        List<DealPipelineValueDto> pipelineValues = List.of(
            new DealPipelineValueDto(1, 2.0, 3.0, 4));
        List<DealAgingDto> aging = List.of(new DealAgingDto(1, 2, 3, 4, 5));
        DealTopDto top = new DealTopDto(List.of(), List.of());
        when(dealService.getDealKpis("JPY", 30, allTeam)).thenReturn(kpis);
        when(dealService.getDealPipelineValue("JPY", 365, allTeam)).thenReturn(pipelineValues);
        when(dealService.getDealAging(null, allTeam)).thenReturn(aging);
        when(dealService.getTopDeals(null, allTeam)).thenReturn(top);

        assertSame(kpis, controller.getDealKpis(
            "JPY", "30d", null, null, null, null, null, null, null));
        assertSame(pipelineValues, controller.getDealPipelineValue(
            "JPY", "12m", null, null, null, null, null, null));
        assertSame(aging, controller.getDealAging("  ", null, null));
        assertSame(top, controller.getTopDeals("", null, null));

        controller.getDealKpis(" ", null, null, null, null, null, "month", "Mars/Olympus", "+09:00");

        verify(dealService).getDealKpis("JPY", 30, allTeam);
        verify(dealService).getDealPipelineValue("JPY", 365, allTeam);
        verify(dealService).getDealAging(null, allTeam);
        verify(dealService).getTopDeals(null, allTeam);
        verify(dealService).getDealKpis(null, 90, allTeam);
    }

    @Test
    void dealAnalyticsEndpointsRejectInvalidRange() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService, memberScopeResolver);

        assertThrows(BadRequestException.class, () -> controller.getDealKpis(
            null, "7d", null, null, null, null, null, null, null));
        assertThrows(BadRequestException.class, () -> controller.getDealPipelineValue(
            null, "all", null, null, null, null, null, null));

        verify(dealService, never()).getDealKpis(any(), anyInt(), any());
        verify(dealService, never()).getDealPipelineValue(any(), anyInt(), any());
    }

    @Test
    void legacyAnalyticsEndpointsPreserveEverySupportedRange() {
        DealController dealController = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService, memberScopeResolver);
        ActivityController activityController = new ActivityController(
            activityService, workspaceService, memberScopeResolver);
        MemberScope allTeam = MemberScope.allTeam();
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(memberScopeResolver.resolve(null, null, 7)).thenReturn(allTeam);
        List<String> ranges = List.of("30d", "90d", "12m");
        List<Integer> days = List.of(30, 90, 365);

        for (String range : ranges) {
            dealController.getDealKpis(
                null, range, null, null, null, null, null, null, null);
            dealController.getDealPipelineValue(
                null, range, null, null, null, null, null, null);
            activityController.getActivityVolume(
                range, null, null, null, null, null, null, null);
            activityController.getTeamLeaderboard(
                range, null, null, null, null);
        }

        for (int dayCount : days) {
            verify(dealService).getDealKpis(null, dayCount, allTeam);
            verify(dealService).getDealPipelineValue(null, dayCount, allTeam);
            verify(activityService).getActivityVolume(dayCount, allTeam);
            verify(activityService).getTeamLeaderboard(dayCount);
        }
    }

    @Test
    void windowedAnalyticsEndpointsDispatchWithServerOwnedTimezone() {
        DealController dealController = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService, memberScopeResolver);
        ActivityController activityController = new ActivityController(
            activityService, workspaceService, memberScopeResolver);
        MemberScope allTeam = MemberScope.allTeam();
        DealKpisDto kpis = new DealKpisDto(
            0.0, null, 0.0, null, 0, 0, 0.0, 0.0, null, null, 0.0, null,
            List.of(), List.of(), List.of(), List.of());
        DealRevenuePeriodSeriesDto revenue = new DealRevenuePeriodSeriesDto(List.of(), List.of());
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(workspaceService.getCurrentAnalyticsTimezone()).thenReturn("Pacific/Honolulu");
        when(memberScopeResolver.resolve(null, null, 7)).thenReturn(allTeam);
        when(dealService.getDealKpis(any(), any(Window.class), anyList(), eq(allTeam))).thenReturn(kpis);
        when(dealService.getRevenueSeries(any(), any(Window.class), anyList(), eq(allTeam))).thenReturn(revenue);
        when(activityService.getActivityVolume(any(Window.class), anyList(), eq(allTeam))).thenReturn(List.of());
        when(activityService.getTeamLeaderboard(any(Window.class))).thenReturn(List.of());

        assertSame(kpis, dealController.getDealKpis(
            "JPY", "invalid-ignored", null, null,
            "2026-03-08", "2026-03-09", "day", "America/New_York", null));
        dealController.getDealPipelineValue(
            "JPY", "invalid-ignored", null, null,
            "2026-03-08", "2026-03-09", null, "+09:00");
        assertSame(revenue, dealController.getRevenueSeries(
            "2026-03-08", "2026-03-09", "day", "JPY",
            null, null, null, null));
        assertTrue(activityController.getActivityVolume(
            "invalid-ignored", null, null,
            "2026-03-08", "2026-03-09", "day", null, null).isEmpty());
        assertTrue(activityController.getTeamLeaderboard(
            "invalid-ignored", "2026-03-08", "2026-03-09", null, null).isEmpty());

        verify(dealService).getDealKpis(
            eq("JPY"),
            argThat(window -> "Pacific/Honolulu".equals(window.timezone().getId())),
            anyList(),
            eq(allTeam));
        verify(dealService).getDealPipelineValue(
            eq("JPY"),
            argThat(window -> "Pacific/Honolulu".equals(window.timezone().getId())),
            eq(allTeam));
        verify(dealService).getRevenueSeries(
            eq("JPY"),
            argThat(window -> "Pacific/Honolulu".equals(window.timezone().getId())),
            anyList(),
            eq(allTeam));
        verify(activityService).getActivityVolume(
            argThat(window -> "Pacific/Honolulu".equals(window.timezone().getId())),
            anyList(),
            eq(allTeam));
        verify(activityService).getTeamLeaderboard(
            argThat(window -> "Pacific/Honolulu".equals(window.timezone().getId())));
    }

    @Test
    void windowValidationRejectsInvalidCommonParameters() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService, memberScopeResolver);

        assertThrows(BadRequestException.class, () -> controller.getDealKpis(
            null, "90d", null, null,
            "2026-01-01", null, "day", null, null));
        assertThrows(BadRequestException.class, () -> controller.getDealKpis(
            null, "90d", null, null,
            "2026-02-01", "2026-01-01", "day", null, null));
        assertThrows(BadRequestException.class, () -> controller.getDealKpis(
            null, "90d", null, null,
            "2026-01-01", "2026-01-31", null, null, null));
        assertThrows(BadRequestException.class, () -> controller.getDealKpis(
            null, "90d", null, null,
            "2026-01-01", "2026-01-31", "quarter", null, null));
        assertThrows(BadRequestException.class, () -> controller.getDealKpis(
            null, "90d", null, null,
            "2026-01-01", "2026-05-01", "day", null, null));
        assertThrows(BadRequestException.class, () -> controller.getDealPipelineValue(
            null, "90d", null, null,
            "2026-01-01", "2028-01-02", null, null));
        assertThrows(BadRequestException.class, () -> controller.getDealPipelineValue(
            null, "90d", null, null,
            "bad-date", "2026-01-01", null, null));
    }

    @Test
    void dealClosingSoonCountValidatesAndDelegatesDays() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService, memberScopeResolver);
        CountDto count = new CountDto(4);
        when(dealService.getClosingSoonCount(7)).thenReturn(count);

        assertSame(count, controller.getClosingSoonCount(7));
        assertThrows(BadRequestException.class, () -> controller.getClosingSoonCount(0));

        verify(dealService).getClosingSoonCount(7);
        verify(dealService, never()).getClosingSoonCount(0);
    }

    @Test
    void dealClosingSoonListValidatesAndDelegatesBounds() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService, memberScopeResolver);
        when(dealService.getClosingSoonDeals(7, 6)).thenReturn(List.of());

        assertEquals(List.of(), controller.getClosingSoonDeals(7, 6));
        assertThrows(BadRequestException.class, () -> controller.getClosingSoonDeals(0, 6));
        assertThrows(BadRequestException.class, () -> controller.getClosingSoonDeals(7, 0));
        assertThrows(BadRequestException.class, () -> controller.getClosingSoonDeals(7, 51));

        verify(dealService).getClosingSoonDeals(7, 6);
        verify(dealService, never()).getClosingSoonDeals(0, 6);
    }

    @Test
    void interactiveDealRiskRequiresIdsAndAnalyticsUsesBoundedProjection() {
        DealController controller = new DealController(
            dealService, bulkOperationService, dealRiskService, dealBriefService,
            dealRiskRationaleService, workspaceService, memberScopeResolver);
        MemberScope allTeam = MemberScope.allTeam();
        DealRiskAnalyticsDto analytics = new DealRiskAnalyticsDto(List.of(), false);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(memberScopeResolver.resolve(null, null, 7)).thenReturn(allTeam);
        when(dealRiskService.analytics(7, allTeam)).thenReturn(analytics);

        assertThrows(BadRequestException.class, () -> controller.getDealRisks(null));
        assertSame(analytics, controller.getDealRiskAnalytics(null, null));

        verify(dealRiskService, never()).assessWorkspace(anyInt());
        verify(dealRiskService).analytics(7, allTeam);
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
        TaskController controller = new TaskController(taskService, workspaceService, memberScopeResolver);

        assertThrows(BadRequestException.class, () -> controller.getTasks(null, null, null));

        verify(taskService, never()).getAllTasks();
    }

    @Test
    void tasksPageClampsSize() {
        TaskController controller = new TaskController(taskService, workspaceService, memberScopeResolver);
        when(taskService.getTasksPage(100, 0)).thenReturn(List.of());
        when(taskService.countTasks()).thenReturn(0L);

        var response = controller.getTasksPage(0, 500);

        assertEquals(0, response.total());
        verify(taskService).getTasksPage(100, 0);
    }

    @Test
    void taskSummaryDelegatesToService() {
        TaskController controller = new TaskController(taskService, workspaceService, memberScopeResolver);
        MemberScope allTeam = MemberScope.allTeam();
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(memberScopeResolver.resolve(null, null, 7)).thenReturn(allTeam);
        TaskSummaryDto summary = new TaskSummaryDto(1, 2, 3, 4, 5);
        when(taskService.getTaskSummary(allTeam)).thenReturn(summary);

        assertSame(summary, controller.getTaskSummary(null, null));
    }

    @Test
    void upcomingTasksAreBoundedBeforeDelegation() {
        TaskController controller = new TaskController(taskService, workspaceService, memberScopeResolver);
        when(taskService.getUpcomingOpenTasks(4)).thenReturn(List.of());

        assertTrue(controller.getUpcomingTasks(4).isEmpty());
        assertThrows(BadRequestException.class, () -> controller.getUpcomingTasks(21));
        verify(taskService).getUpcomingOpenTasks(4);
    }

    @Test
    void activitiesWithoutFilterOrPaginationRequirePageEndpoint() {
        ActivityController controller = new ActivityController(
            activityService, workspaceService, memberScopeResolver);

        assertThrows(BadRequestException.class, () -> controller.getActivities(null, null, null, null, null));

        verify(activityService, never()).getAllActivities();
    }

    @Test
    void activitiesPageClampsSize() {
        ActivityController controller = new ActivityController(
            activityService, workspaceService, memberScopeResolver);
        when(activityService.getActivitiesPage(null, null, null, 100, 0)).thenReturn(List.of());
        when(activityService.countActivities(null, null, null)).thenReturn(0L);

        var response = controller.getActivitiesPage(0, 500, null, null, null);

        assertEquals(0, response.total());
        verify(activityService).getActivitiesPage(null, null, null, 100, 0);
    }

    @Test
    void activityAnalyticsRangesAndDaysAreValidatedBeforeDelegation() {
        ActivityController controller = new ActivityController(
            activityService, workspaceService, memberScopeResolver);
        MemberScope allTeam = MemberScope.allTeam();
        when(workspaceService.getCurrentUserId()).thenReturn(7);
        when(memberScopeResolver.resolve(null, null, 7)).thenReturn(allTeam);
        when(activityService.getActivityVolume(30, allTeam)).thenReturn(List.of());
        when(activityService.getTeamLeaderboard(365)).thenReturn(List.of());
        when(activityService.getUpcomingCount(7)).thenReturn(new CountDto(2));

        assertTrue(controller.getActivityVolume(
            "30d", null, null, null, null, null, null, null).isEmpty());
        assertTrue(controller.getTeamLeaderboard(
            "12m", null, null, null, null).isEmpty());
        assertEquals(2, controller.getUpcomingCount(7).count());
        assertThrows(BadRequestException.class, () -> controller.getActivityVolume(
            "7d", null, null, null, null, null, null, null));
        assertThrows(BadRequestException.class, () -> controller.getTeamLeaderboard(
            "all", null, null, null, null));
        assertThrows(BadRequestException.class, () -> controller.getUpcomingCount(0));

        verify(activityService).getActivityVolume(30, allTeam);
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

    private PersonController personController() {
        return new PersonController(
            personService, employmentService, connectionService, bulkOperationService,
            workspaceService, memberScopeResolver);
    }

    private CompanyController companyController() {
        return new CompanyController(
            companyService, bulkOperationService, workspaceService, memberScopeResolver);
    }
}
