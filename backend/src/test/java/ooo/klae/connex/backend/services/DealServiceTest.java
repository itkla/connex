package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealStageHistory;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.RecordCommentThread;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DealAgingDto;
import ooo.klae.connex.backend.dto.DealCurrencyMetricsDto;
import ooo.klae.connex.backend.dto.DealDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.DealFacets;
import ooo.klae.connex.backend.dto.DealKpisDto;
import ooo.klae.connex.backend.dto.DealLineItemRequest;
import ooo.klae.connex.backend.dto.DealMetricsDto;
import ooo.klae.connex.backend.dto.DealMonthTotalDto;
import ooo.klae.connex.backend.dto.DealPipelineValueDto;
import ooo.klae.connex.backend.dto.DealPrimaryContactDto;
import ooo.klae.connex.backend.dto.DealRevenuePeriodSeriesDto;
import ooo.klae.connex.backend.dto.DealRevenueSeriesDto;
import ooo.klae.connex.backend.dto.DealStageDistributionDto;
import ooo.klae.connex.backend.dto.DealSummaryDto;
import ooo.klae.connex.backend.dto.DealTopDto;
import ooo.klae.connex.backend.dto.DuplicatePreflightResponse;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.RecordCommentMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.util.AnalyticsPeriods;
import ooo.klae.connex.backend.util.AnalyticsPeriods.Window;

@RecordApplicationEvents
class DealServiceTest extends AbstractServiceTest {

    @Autowired DealService dealService;
    @Autowired DealLineItemService dealLineItemService;
    @Autowired BulkOperationService bulkOperationService;
    @Autowired DuplicatePreflightService duplicatePreflightService;
    @Autowired RecordCommentService recordCommentService;
    @Autowired RuleActionExecutor ruleActionExecutor;
    @Autowired AuditService auditService;
    @Autowired ApplicationEvents applicationEvents;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired ShareMapper shareMapper;
    @MockitoSpyBean DealMapper dealMapperSpy;
    @MockitoSpyBean RecordCommentMapper recordCommentMapperSpy;
    @MockitoSpyBean NotificationChangePublisher notificationChanges;
    @MockitoSpyBean RuleTriggerPublisher ruleTriggers;

    @Test
    void removeTagIsIdempotentWhenTagNoLongerExists() {
        Pipeline pipeline = newPipeline();
        Deal deal = newDeal(pipeline, newStage(pipeline, 0), newCompany());
        int auditBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ?",
            Integer.class,
            workspace.getId());

        assertDoesNotThrow(
            () -> dealService.removeTag(deal.getId(), Integer.MAX_VALUE));

        assertEquals(auditBefore + 1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ?",
            Integer.class,
            workspace.getId()));
    }

    @Test
    void conditionalTagRemovalRefusesOnceTheAssociationChanged() {
        Pipeline pipeline = newPipeline();
        Deal deal = newDeal(pipeline, newStage(pipeline, 0), newCompany());
        Tag tag = newTag();
        dealService.addTag(deal.getId(), tag.getId());

        assertDoesNotThrow(() -> dealService.removeTagIfUnchanged(deal.getId(), tag.getId()));
        assertThrows(
            ConflictException.class,
            () -> dealService.removeTagIfUnchanged(deal.getId(), tag.getId()));
    }

    @Test
    void deleteRemovesOnlyTheDealsCommentThreadsAndTheirCascadedComments() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deletedDeal = newDeal(pipeline, stage, company);
        Deal retainedDeal = newDeal(pipeline, stage, company);
        RecordCommentThread deletedThread = recordCommentService.createThread(
            "deal", deletedDeal.getId(), "Deleted deal comment", UUID.randomUUID().toString());
        RecordCommentThread retainedThread = recordCommentService.createThread(
            "deal", retainedDeal.getId(), "Retained deal comment", UUID.randomUUID().toString());
        long deletedCommentId = deletedThread.getComments().getFirst().getId();
        long retainedCommentId = retainedThread.getComments().getFirst().getId();
        clearInvocations(dealMapperSpy, recordCommentMapperSpy);

        dealService.delete(deletedDeal.getId());

        var order = inOrder(recordCommentMapperSpy, dealMapperSpy);
        order.verify(dealMapperSpy).delete(workspace.getId(), deletedDeal.getId());
        order.verify(recordCommentMapperSpy).deleteThreadsForTarget(
            workspace.getId(), "deal", deletedDeal.getId());
        assertNull(recordCommentMapperSpy.getThreadById(workspace.getId(), deletedThread.getId()));
        assertNull(recordCommentMapperSpy.getCommentById(workspace.getId(), deletedCommentId));
        assertNotNull(recordCommentMapperSpy.getThreadById(workspace.getId(), retainedThread.getId()));
        assertNotNull(recordCommentMapperSpy.getCommentById(workspace.getId(), retainedCommentId));
    }

    @Test
    void aggregateReadsAreAssembledAndIsolatedByWorkspace() {
        Workspace activeWorkspace = newWorkspace();
        workspaceMapper.addMember(activeWorkspace.getId(), currentUser.getId(), "owner");
        workspace = activeWorkspace;
        authenticateAs(currentUser, workspace.getId());

        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        updateDeal(newDeal(pipeline, stage, company), "Local Open", 100.0, 0.0, "JPY", null);
        Deal localWon = updateDeal(
            newDeal(pipeline, stage, company), "Local Won High", 120.0, 90.0, "JPY", true);
        Deal localWonLower = updateDeal(
            newDeal(pipeline, stage, company), "Local Won Low", 110.0, 80.0, "JPY", true);
        Person contact = personNamed("Local Contact");
        dealService.addPerson(localWon.getId(), contact.getId(), "champion");

        Workspace otherWorkspace = newWorkspace();
        Pipeline otherPipeline = newPipelineIn(otherWorkspace);
        Stage otherStage = newStageIn(otherWorkspace, otherPipeline);
        Company otherCompany = newCompanyIn(otherWorkspace);
        Deal foreign = new Deal();
        foreign.setWorkspaceId(otherWorkspace.getId());
        foreign.setName("Foreign Won");
        foreign.setValue(new BigDecimal("1000.00"));
        foreign.setActualValue(new BigDecimal("900.00"));
        foreign.setCurrency("USD");
        foreign.setPipelineId(otherPipeline.getId());
        foreign.setStageId(otherStage.getId());
        foreign.setCompanyId(otherCompany.getId());
        foreign.setWon(true);
        foreign.setClosedAt("2026-01-01 00:00:00");
        dealMapper.insert(foreign);

        MemberScope allTeamScope = MemberScope.fromRequest(null, null, currentUser.getId());
        DealMetricsDto metrics = dealService.getDealMetrics(null, null, null, null, null, null);
        DealFacets facets = dealService.getDealFacets();
        List<Deal> page = dealService.getDealsPage(
            null, null, null, null, null, null, null, null, 25, 0);
        long count = dealService.countDeals(null, null, null, null, null, null);
        List<Deal> filteredPage = dealService.getDealsPage(
            "%Local Won%", "value", "desc", "JPY", pipeline.getId(), stage.getId(),
            company.getId(), "won", 25, 0);
        long filteredCount = dealService.countDeals(
            "%Local Won%", "JPY", pipeline.getId(), stage.getId(), company.getId(), "won");
        DealMetricsDto filteredMetrics = dealService.getDealMetrics(
            "%Local Won%", "JPY", pipeline.getId(), stage.getId(), company.getId(), "won");
        var multiFilteredPage = dealService.queryDealsPage(
            "%Local Won%", "value", "desc", "JPY",
            List.of(pipeline.getId()), List.of(stage.getId()), List.of(company.getId()),
            List.of(contact.getId()), false, List.of("won", "lost"), null,
            allTeamScope, 25, 0);
        DealMetricsDto multiFilteredMetrics = dealService.queryDealMetrics(
            "%Local Won%", "JPY",
            List.of(pipeline.getId()), List.of(stage.getId()), List.of(company.getId()),
            List.of(contact.getId()), false, List.of("won", "lost"), null, allTeamScope);
        List<Integer> matchingIds = dealService.getMatchingDealIds(
            "%Local Won%", "JPY",
            List.of(pipeline.getId()), List.of(stage.getId()), List.of(company.getId()),
            List.of(contact.getId()), false, List.of("won", "lost"), null, allTeamScope);
        List<Deal> export = dealService.queryDealsForExport(
            "%Local Won%", "JPY",
            List.of(pipeline.getId()), List.of(stage.getId()), List.of(company.getId()),
            List.of(contact.getId()), false, List.of("won", "lost"), null, allTeamScope);

        assertEquals(3, metrics.totalCount());
        assertEquals(1, metrics.byCurrency().size());
        DealCurrencyMetricsDto jpy = metrics.byCurrency().get(0);
        assertEquals("JPY", jpy.currency());
        assertEquals(1, jpy.openCount());
        assertEquals(100.0, jpy.openValue(), 0.0001);
        assertEquals(2, jpy.closedCount());
        assertEquals(230.0, jpy.closedForecast(), 0.0001);
        assertEquals(170.0, jpy.closedRevenue(), 0.0001);
        assertEquals(2, jpy.wonCount());
        assertEquals(0, jpy.lostCount());
        assertEquals(Map.of("open", 1L, "won", 2L), facetCounts(facets.status()));
        assertEquals(Map.of(Integer.toString(stage.getId()), 3L), facetCounts(facets.stages()));
        assertEquals(Map.of(Integer.toString(pipeline.getId()), 3L), facetCounts(facets.pipelines()));
        assertEquals(Map.of(Integer.toString(company.getId()), 3L), facetCounts(facets.companies()));
        assertEquals(Map.of(Integer.toString(contact.getId()), 1L), facetCounts(facets.people()));
        assertEquals(Map.of(Integer.toString(currentUser.getId()), 3L), facetCounts(facets.owners()));
        assertEquals(Map.of("JPY", 3L), facetCounts(facets.currencies()));
        assertEquals(3, count);
        assertEquals(3, page.size());
        assertTrue(page.stream().anyMatch(deal -> deal.getId() == localWon.getId()));
        assertTrue(page.stream().noneMatch(deal -> deal.getId() == foreign.getId()));
        assertEquals(2, filteredCount);
        assertEquals(List.of(localWon.getId(), localWonLower.getId()),
            filteredPage.stream().map(Deal::getId).toList());
        assertEquals(2, filteredMetrics.totalCount());
        assertEquals(170.0, filteredMetrics.byCurrency().get(0).closedRevenue(), 0.0001);
        assertEquals(1, multiFilteredPage.total());
        assertEquals(List.of(localWon.getId()),
            multiFilteredPage.items().stream().map(Deal::getId).toList());
        assertEquals(1, multiFilteredMetrics.totalCount());
        assertEquals(List.of(localWon.getId()), matchingIds);
        assertEquals(List.of(localWon.getId()), export.stream().map(Deal::getId).toList());
        assertEquals(3, dealService.getDealBoard(pipeline.getId()).size());
    }

    @Test
    void segmentReadsIntersectNativeFiltersAndFailClosedWhenEmpty() {
        Workspace activeWorkspace = newWorkspace();
        workspaceMapper.addMember(activeWorkspace.getId(), currentUser.getId(), "owner");
        workspace = activeWorkspace;
        authenticateAs(currentUser, workspace.getId());
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        String segmentName = "Segment " + unique();
        updateDeal(newDeal(pipeline, stage, company), segmentName, 100.0, 0.0, "JPY", null);
        Deal won = updateDeal(
            newDeal(pipeline, stage, company), segmentName, 200.0, 180.0, "JPY", true);
        Person contact = personNamed("Segment Contact");
        dealService.addPerson(won.getId(), contact.getId(), "champion");
        SegmentDefinition definition = segmentDefinition(
            segmentField("name", "equals", segmentName));
        SegmentDefinition empty = segmentDefinition(
            segmentField("name", "equals", "Missing " + unique()));
        MemberScope scope = MemberScope.allTeam();

        PageResponse<Deal> page = dealService.querySegmentDealsPage(
            definition, null, null, null, null, null, null, null, List.of(contact.getId()),
            false, List.of("won"), null, scope, 25, 0);
        DealMetricsDto metrics = dealService.querySegmentDealMetrics(
            definition, null, null, null, null, null, List.of(contact.getId()),
            false, List.of("won"), null, scope);
        List<Integer> ids = dealService.getMatchingSegmentDealIds(
            definition, null, null, null, null, null, List.of(contact.getId()),
            false, List.of("won"), null, scope);
        List<Deal> export = dealService.querySegmentDealsForExport(
            definition, null, null, null, null, null, List.of(contact.getId()),
            false, List.of("won"), null, scope);

        assertEquals(List.of(won.getId()), page.items().stream().map(Deal::getId).toList());
        assertEquals(1, page.total());
        assertEquals(1, metrics.totalCount());
        assertEquals(List.of(won.getId()), ids);
        assertEquals(List.of(won.getId()), export.stream().map(Deal::getId).toList());
        assertEquals(0, dealService.querySegmentDealsPage(
            empty, null, null, null, null, null, null, null, List.of(contact.getId()),
            false, null, null, scope, 25, 0).total());
        assertEquals(0, dealService.querySegmentDealMetrics(
            empty, null, null, null, null, null, List.of(contact.getId()),
            false, null, null, scope).totalCount());
        assertTrue(dealService.getMatchingSegmentDealIds(
            empty, null, null, null, null, null, List.of(contact.getId()),
            false, null, null, scope).isEmpty());
        assertTrue(dealService.querySegmentDealsForExport(
            empty, null, null, null, null, null, List.of(contact.getId()),
            false, null, null, scope).isEmpty());
    }

    @Test
    void matchingDealIdsRejectMoreThanBulkOperationLimit() {
        Workspace activeWorkspace = newWorkspace();
        workspaceMapper.addMember(activeWorkspace.getId(), currentUser.getId(), "owner");
        workspace = activeWorkspace;
        authenticateAs(currentUser, workspace.getId());
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        List<Deal> deals = IntStream.rangeClosed(1, 1001)
            .mapToObj(position -> boardDeal(pipeline, stage, position))
            .toList();
        dealMapper.insertBatch(deals);
        SegmentDefinition definition = segmentDefinition(
            segmentField("name", "starts_with", "Bounded Board "));

        BadRequestException nativeException = assertThrows(BadRequestException.class,
            () -> dealService.getMatchingDealIds(
                null, null, null, null, null, null, false, null, null, MemberScope.allTeam()));
        BadRequestException segmentException = assertThrows(BadRequestException.class,
            () -> dealService.getMatchingSegmentDealIds(
                definition, null, null, null, null, null, null,
                false, null, null, MemberScope.allTeam()));

        assertEquals("Too many matching deals; narrow the filters before selecting all",
            nativeException.getMessage());
        assertEquals(nativeException.getMessage(), segmentException.getMessage());
    }

    @Test
    void oversizedBoardRejectsKanbanReordering() {
        Workspace activeWorkspace = newWorkspace();
        workspaceMapper.addMember(activeWorkspace.getId(), currentUser.getId(), "owner");
        workspace = activeWorkspace;
        authenticateAs(currentUser, workspace.getId());
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        List<Deal> deals = IntStream.rangeClosed(1, 2001)
            .mapToObj(position -> boardDeal(pipeline, stage, position))
            .toList();
        deals.get(0).setOwnerId(currentUser.getId());
        dealMapper.insertBatch(deals);

        BadRequestException exception = assertThrows(BadRequestException.class,
            () -> dealService.getDealBoard(pipeline.getId()));

        assertEquals("This pipeline is too large for Kanban reordering; use the paginated table view",
            exception.getMessage());
    }

    @Test
    void chartAggregatesAreAssembledAndIsolatedByWorkspace() {
        Workspace activeWorkspace = newWorkspace();
        workspaceMapper.addMember(activeWorkspace.getId(), currentUser.getId(), "owner");
        workspace = activeWorkspace;
        authenticateAs(currentUser, workspace.getId());

        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        updateChartDeal(newDeal(pipeline, stage, company),
            100.0, 0.0, "JPY", null, "2026-02-10", null);
        updateChartDeal(newDeal(pipeline, stage, company),
            200.0, 180.0, "JPY", true, "2026-02-15", "2026-01-10 00:00:00");
        updateChartDeal(newDeal(pipeline, stage, company),
            50.0, 25.0, "JPY", false, "2026-03-10", "2026-03-20 00:00:00");
        updateChartDeal(newDeal(pipeline, stage, company),
            400.0, 0.0, "USD", null, "2026-04-15", null);
        updateChartDeal(newDeal(pipeline, stage, company),
            600.0, 550.0, "USD", true, null, "2026-04-20 00:00:00");

        Workspace otherWorkspace = newWorkspace();
        Pipeline otherPipeline = newPipelineIn(otherWorkspace);
        Stage otherStage = newStageIn(otherWorkspace, otherPipeline);
        Deal foreign = new Deal();
        foreign.setWorkspaceId(otherWorkspace.getId());
        foreign.setName("Foreign Won");
        foreign.setValue(new BigDecimal("1000.00"));
        foreign.setActualValue(new BigDecimal("900.00"));
        foreign.setCurrency("USD");
        foreign.setPipelineId(otherPipeline.getId());
        foreign.setStageId(otherStage.getId());
        foreign.setExpectedCloseDate("2026-02-20");
        foreign.setClosedAt("2026-01-25 00:00:00");
        foreign.setWon(true);
        dealMapper.insert(foreign);

        DealRevenueSeriesDto series = dealService.getRevenueTimeseries(null, null, MemberScope.allTeam());
        List<DealStageDistributionDto> distribution = dealService.getStageDistribution(null, MemberScope.allTeam());
        DealRevenueSeriesDto filteredSeries = dealService.getRevenueTimeseries("JPY", null, MemberScope.allTeam());
        List<DealStageDistributionDto> filteredDistribution = dealService.getStageDistribution("JPY", MemberScope.allTeam());

        assertEquals(Map.of("2026-2", 180.0, "2026-3", 25.0, "2026-4", 550.0),
            monthTotals(series.closed()));
        assertEquals(Map.of("2026-2", 300.0, "2026-3", 50.0, "2026-4", 400.0),
            monthTotals(series.projected()));
        assertEquals(1, distribution.size());
        DealStageDistributionDto stageTotals = distribution.get(0);
        assertEquals(stage.getId(), stageTotals.stageId());
        assertEquals(pipeline.getId(), stageTotals.pipelineId());
        assertEquals(2, stageTotals.openCount());
        assertEquals(500.0, stageTotals.openValue(), 0.0001);
        assertEquals(3, stageTotals.closedCount());
        assertEquals(780.0, stageTotals.closedValue(), 0.0001);
        assertEquals(Map.of("2026-2", 180.0, "2026-3", 25.0),
            monthTotals(filteredSeries.closed()));
        assertEquals(Map.of("2026-2", 300.0, "2026-3", 50.0),
            monthTotals(filteredSeries.projected()));
        assertEquals(1, filteredDistribution.size());
        assertEquals(1, filteredDistribution.get(0).openCount());
        assertEquals(100.0, filteredDistribution.get(0).openValue(), 0.0001);
        assertEquals(2, filteredDistribution.get(0).closedCount());
        assertEquals(230.0, filteredDistribution.get(0).closedValue(), 0.0001);
    }

    @Test
    void revenueTimeseriesUsesHistoricalIanaRulesPerClosedTimestamp() {
        Workspace activeWorkspace = newWorkspace();
        workspaceMapper.addMember(activeWorkspace.getId(), currentUser.getId(), "owner");
        workspace = activeWorkspace;
        authenticateAs(currentUser, workspace.getId());
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        updateChartDeal(newDeal(pipeline, stage, company),
            100.0, 90.0, "USD", true, null, "2026-01-01 04:30:00");
        updateChartDeal(newDeal(pipeline, stage, company),
            20.0, 10.0, "USD", false, "2026-07-15", "2026-07-01 03:30:00");

        DealRevenueSeriesDto utc = dealService.getRevenueTimeseries("USD", "UTC", MemberScope.allTeam());
        DealRevenueSeriesDto newYork = dealService.getRevenueTimeseries("USD", "America/New_York", MemberScope.allTeam());

        assertEquals(Map.of("2026-1", 90.0, "2026-7", 10.0), monthTotals(utc.closed()));
        assertEquals(Map.of("2025-12", 90.0, "2026-6", 10.0), monthTotals(newYork.closed()));
        assertEquals(Map.of("2026-1", 90.0, "2026-7", 10.0),
            monthTotals(dealService.getRevenueTimeseries("USD", "+09:00", MemberScope.allTeam()).closed()));
    }

    @Test
    void analyticsAggregatesAssembleSeriesSummariesAndStayWorkspaceScoped() {
        Workspace activeWorkspace = newWorkspace();
        workspaceMapper.addMember(activeWorkspace.getId(), currentUser.getId(), "owner");
        workspace = activeWorkspace;
        authenticateAs(currentUser, workspace.getId());

        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal currentWon = analyticsDeal(workspace, pipeline, stage, company, "Current Won",
            100.0, 80.0, "JPY", true, 11, 1, 1);
        analyticsDeal(workspace, pipeline, stage, company, "Current Lost",
            50.0, 0.0, "JPY", false, 12, 1, 1);
        Deal open = analyticsDeal(workspace, pipeline, stage, company, "Current Open",
            30.0, 0.0, "JPY", null, 3, null, 61);
        Deal previousWon = analyticsDeal(workspace, pipeline, stage, company, "Previous Won",
            60.0, 40.0, "JPY", true, 50, 40, 1);
        analyticsDeal(workspace, pipeline, stage, company, "Previous Lost",
            20.0, 0.0, "JPY", false, 55, 45, 1);
        analyticsDeal(workspace, pipeline, stage, company, "Other Currency",
            900.0, 800.0, "USD", true, 10, 1, 1);

        Workspace otherWorkspace = newWorkspace();
        Pipeline otherPipeline = newPipelineIn(otherWorkspace);
        Stage otherStage = newStageIn(otherWorkspace, otherPipeline);
        Company otherCompany = newCompanyIn(otherWorkspace);
        analyticsDeal(otherWorkspace, otherPipeline, otherStage, otherCompany, "Foreign Won",
            5000.0, 4000.0, "JPY", true, 11, 1, 1);
        analyticsDeal(otherWorkspace, otherPipeline, otherStage, otherCompany, "Foreign Open",
            6000.0, 0.0, "JPY", null, 3, null, 61);

        DealKpisDto kpis = dealService.getDealKpis("JPY", 30, MemberScope.allTeam());
        List<DealPipelineValueDto> pipelineValues = dealService.getDealPipelineValue("JPY", 30, MemberScope.allTeam());
        List<DealAgingDto> aging = dealService.getDealAging("JPY", MemberScope.allTeam());
        DealTopDto top = dealService.getTopDeals("JPY", MemberScope.allTeam());

        assertEquals(80.0, kpis.wonRevenue(), 0.0001);
        assertEquals(40.0, kpis.wonRevenuePrev(), 0.0001);
        assertEquals(180.0, kpis.newPipeline(), 0.0001);
        assertEquals(80.0, kpis.newPipelinePrev(), 0.0001);
        assertEquals(1, kpis.wonCount());
        assertEquals(1, kpis.lostCount());
        assertEquals(80.0, kpis.wonValue(), 0.0001);
        assertEquals(50.0, kpis.lostValue(), 0.0001);
        assertEquals(1L, kpis.wonCountPrev());
        assertEquals(1L, kpis.lostCountPrev());
        assertEquals(10.0, kpis.avgCycleDays(), 0.0001);
        assertEquals(10.0, kpis.avgCycleDaysPrev(), 0.0001);
        assertEquals(12, kpis.wonSeries().size());
        assertEquals(80.0, kpis.wonSeries().get(11), 0.0001);
        assertEquals(150.0, kpis.newPipelineSeries().get(7), 0.0001);
        assertEquals(30.0, kpis.newPipelineSeries().get(10), 0.0001);
        assertEquals(50.0, kpis.winRateSeries().get(11), 0.0001);
        assertEquals(10.0, kpis.avgCycleSeries().get(11), 0.0001);

        assertEquals(1, pipelineValues.size());
        assertEquals(pipeline.getId(), pipelineValues.get(0).pipelineId());
        assertEquals(80.0, pipelineValues.get(0).wonValue(), 0.0001);
        assertEquals(30.0, pipelineValues.get(0).openValue(), 0.0001);
        assertEquals(1, pipelineValues.get(0).openCount());
        assertEquals(1, aging.size());
        assertEquals(stage.getId(), aging.get(0).stageId());
        assertEquals(1, aging.get(0).stalled());
        assertEquals(List.of(open.getId()), top.topOpen().stream().map(DealSummaryDto::getId).toList());
        assertEquals(List.of(currentWon.getId(), previousWon.getId()),
            top.topWon().stream().map(DealSummaryDto::getId).toList());
        List<BigDecimal> topWonActuals = top.topWon().stream()
            .map(DealSummaryDto::getActualValue).toList();
        assertEquals(2, topWonActuals.size());
        assertEquals(0, new BigDecimal("80.00").compareTo(topWonActuals.get(0)));
        assertEquals(0, new BigDecimal("40.00").compareTo(topWonActuals.get(1)));
        assertTrue(top.topWon().stream().allMatch(summary -> company.getName().equals(summary.getCompanyName())));
    }

    @Test
    void dealKpisUseNullPreviousBaselinesWithoutQualifyingDeals() {
        Workspace emptyWorkspace = newWorkspace();
        workspaceMapper.addMember(emptyWorkspace.getId(), currentUser.getId(), "owner");
        workspace = emptyWorkspace;
        authenticateAs(currentUser, workspace.getId());

        DealKpisDto kpis = dealService.getDealKpis("JPY", 90, MemberScope.allTeam());

        assertEquals(0.0, kpis.wonRevenue(), 0.0001);
        assertEquals(0.0, kpis.newPipeline(), 0.0001);
        assertEquals(0.0, kpis.avgCycleDays(), 0.0001);
        assertNull(kpis.wonRevenuePrev());
        assertNull(kpis.newPipelinePrev());
        assertNull(kpis.wonCountPrev());
        assertNull(kpis.lostCountPrev());
        assertNull(kpis.avgCycleDaysPrev());
        assertEquals(12, kpis.wonSeries().size());
        assertTrue(kpis.wonSeries().stream().allMatch(value -> value == 0.0));
        assertTrue(kpis.newPipelineSeries().stream().allMatch(value -> value == 0.0));
        assertTrue(kpis.winRateSeries().stream().allMatch(value -> value == 0.0));
        assertTrue(kpis.avgCycleSeries().stream().allMatch(value -> value == 0.0));
    }

    @Test
    void dealKpisUseMetricSpecificPreviousBaselineDenominators() {
        Workspace activeWorkspace = newWorkspace();
        workspaceMapper.addMember(activeWorkspace.getId(), currentUser.getId(), "owner");
        workspace = activeWorkspace;
        authenticateAs(currentUser, workspace.getId());
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        analyticsDeal(workspace, pipeline, stage, company, "Previous Zero Lost",
            0.0, 0.0, "JPY", false, 45, 40, 1);

        DealKpisDto lostOnly = dealService.getDealKpis("JPY", 30, MemberScope.allTeam());

        assertNull(lostOnly.wonRevenuePrev());
        assertNull(lostOnly.avgCycleDaysPrev());
        assertEquals(0.0, lostOnly.newPipelinePrev(), 0.0001);
        assertEquals(0L, lostOnly.wonCountPrev());
        assertEquals(1L, lostOnly.lostCountPrev());

        analyticsDeal(workspace, pipeline, stage, company, "Previous Zero Won",
            0.0, 0.0, "JPY", true, 50, 40, 1);
        DealKpisDto withWon = dealService.getDealKpis("JPY", 30, MemberScope.allTeam());

        assertEquals(0.0, withWon.wonRevenuePrev(), 0.0001);
        assertEquals(10.0, withWon.avgCycleDaysPrev(), 0.0001);
        assertEquals(1L, withWon.wonCountPrev());
        assertEquals(1L, withWon.lostCountPrev());
    }

    @Test
    void windowedAnalyticsAggregateAlignedPeriodsWithoutNowOrTenantLeakage() {
        Workspace activeWorkspace = newWorkspace();
        workspaceMapper.addMember(activeWorkspace.getId(), currentUser.getId(), "owner");
        workspace = activeWorkspace;
        authenticateAs(currentUser, workspace.getId());
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        windowDeal(activeWorkspace, pipeline, stage, company, "Previous Won",
            70.0, 40.0, "JPY", true,
            LocalDateTime.of(2027, 2, 28, 0, 0),
            LocalDateTime.of(2027, 3, 2, 12, 0), null);
        windowDeal(activeWorkspace, pipeline, stage, company, "Scheduled Won",
            100.0, 80.0, "JPY", true,
            LocalDateTime.of(2027, 3, 5, 10, 0),
            LocalDateTime.of(2027, 3, 6, 12, 0), "2027-03-07");
        windowDeal(activeWorkspace, pipeline, stage, company, "Lost",
            50.0, 25.0, "JPY", false,
            LocalDateTime.of(2027, 3, 6, 10, 0),
            LocalDateTime.of(2027, 3, 8, 12, 0), null);
        windowDeal(activeWorkspace, pipeline, stage, company, "Unscheduled Won",
            120.0, 90.0, "JPY", true,
            LocalDateTime.of(2027, 3, 4, 10, 0),
            LocalDateTime.of(2027, 3, 10, 12, 0), null);
        windowDeal(activeWorkspace, pipeline, stage, company, "Open",
            30.0, 0.0, "JPY", null,
            LocalDateTime.of(2027, 3, 9, 10, 0), null, "2027-03-09");
        windowDeal(activeWorkspace, pipeline, stage, company, "Exclusive End",
            999.0, 999.0, "JPY", true,
            LocalDateTime.of(2027, 3, 11, 0, 0),
            LocalDateTime.of(2027, 3, 11, 0, 0), null);
        windowDeal(activeWorkspace, pipeline, stage, company, "Other Currency",
            800.0, 700.0, "USD", true,
            LocalDateTime.of(2027, 3, 5, 10, 0),
            LocalDateTime.of(2027, 3, 6, 10, 0), null);

        Workspace foreignWorkspace = newWorkspace();
        Pipeline foreignPipeline = newPipelineIn(foreignWorkspace);
        Stage foreignStage = newStageIn(foreignWorkspace, foreignPipeline);
        Company foreignCompany = newCompanyIn(foreignWorkspace);
        windowDeal(foreignWorkspace, foreignPipeline, foreignStage, foreignCompany, "Foreign",
            5000.0, 4000.0, "JPY", true,
            LocalDateTime.of(2027, 3, 5, 10, 0),
            LocalDateTime.of(2027, 3, 6, 10, 0), null);

        Window window = AnalyticsPeriods.requiredWindow(
            "2027-03-05", "2027-03-10", "UTC", null);
        var periods = AnalyticsPeriods.periods(window, "day");
        DealKpisDto kpis = dealService.getDealKpis(
            "JPY", window, periods, MemberScope.allTeam());
        List<DealPipelineValueDto> pipelineValues = dealService.getDealPipelineValue(
            "JPY", window, MemberScope.allTeam());
        DealRevenuePeriodSeriesDto revenue = dealService.getRevenueSeries(
            "JPY", window, periods, MemberScope.allTeam());

        assertEquals(170.0, kpis.wonRevenue(), 0.0001);
        assertEquals(40.0, kpis.wonRevenuePrev(), 0.0001);
        assertEquals(180.0, kpis.newPipeline(), 0.0001);
        assertEquals(190.0, kpis.newPipelinePrev(), 0.0001);
        assertEquals(2, kpis.wonCount());
        assertEquals(1, kpis.lostCount());
        assertEquals(3.5, kpis.avgCycleDays(), 0.0001);
        assertEquals(6, kpis.wonSeries().size());
        assertEquals(List.of(0.0, 80.0, 0.0, 0.0, 0.0, 90.0), kpis.wonSeries());
        assertEquals(100.0, kpis.winRateSeries().get(1), 0.0001);
        assertEquals(1, pipelineValues.size());
        assertEquals(170.0, pipelineValues.getFirst().wonValue(), 0.0001);
        assertEquals(30.0, pipelineValues.getFirst().openValue(), 0.0001);
        assertEquals(1, pipelineValues.getFirst().openCount());
        assertEquals(
            List.of("2027-03-05", "2027-03-06", "2027-03-07",
                "2027-03-08", "2027-03-09", "2027-03-10"),
            revenue.realized().stream().map(total -> total.periodStart()).toList());
        assertEquals(
            List.of(0.0, 0.0, 80.0, 25.0, 0.0, 90.0),
            revenue.realized().stream().map(total -> total.total()).toList());
        assertEquals(
            List.of(0.0, 0.0, 100.0, 0.0, 30.0, 0.0),
            revenue.projected().stream().map(total -> total.total()).toList());
    }

    @Test
    void getActivitiesByDealId_returnsOnlyMatchingActivities() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal d1 = newDeal(pipeline, stage, company);
        Deal d2 = newDeal(pipeline, stage, company);
        User user = newUser();
        Activity a1 = newActivity(user, null, d1);
        Activity a2 = newActivity(user, null, d2);

        List<Activity> activities = dealService.getActivitiesByDealId(d1.getId());

        assertTrue(activities.stream().anyMatch(x -> x.getId() == a1.getId()));
        assertTrue(activities.stream().noneMatch(x -> x.getId() == a2.getId()));
    }

    @Test
    void getActivitiesByDealId_throwsWhenDealMissing() {
        assertThrows(ResourceNotFoundException.class, () -> dealService.getActivitiesByDealId(-1));
    }

    @Test
    void getNotesByDealId_returnsOnlyMatchingNotes() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal d1 = newDeal(pipeline, stage, company);
        Deal d2 = newDeal(pipeline, stage, company);
        User user = newUser();
        Note n1 = newNote(user, null, d1);
        Note n2 = newNote(user, null, d2);

        List<Note> notes = dealService.getNotesByDealId(d1.getId());

        assertTrue(notes.stream().anyMatch(x -> x.getId() == n1.getId()));
        assertTrue(notes.stream().noneMatch(x -> x.getId() == n2.getId()));
    }

    @Test
    void getNotesByDealId_throwsWhenDealMissing() {
        assertThrows(ResourceNotFoundException.class, () -> dealService.getNotesByDealId(-1));
    }

    @Test
    void getTasksByDealId_returnsOnlyMatchingTasks() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal d1 = newDeal(pipeline, stage, company);
        Deal d2 = newDeal(pipeline, stage, company);
        User user = newUser();
        Task t1 = newTask(user, null, d1);
        Task t2 = newTask(user, null, d2);

        List<Task> tasks = dealService.getTasksByDealId(d1.getId());

        assertTrue(tasks.stream().anyMatch(x -> x.getId() == t1.getId()));
        assertTrue(tasks.stream().noneMatch(x -> x.getId() == t2.getId()));
    }

    @Test
    void getTasksByDealId_throwsWhenDealMissing() {
        assertThrows(ResourceNotFoundException.class, () -> dealService.getTasksByDealId(-1));
    }

    @Test
    void getDealSummary_resolvesNames() {
        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);

        DealSummaryDto summary = dealService.getDealSummary(deal.getId());

        assertEquals(deal.getId(), summary.getId());
        assertEquals(0, deal.getActualValue().compareTo(summary.getActualValue()));
        assertEquals(pipeline.getName(), summary.getPipelineName());
        assertEquals(stage.getName(), summary.getStageName());
        assertEquals(company.getName(), summary.getCompanyName());
        assertEquals(currentUser.getDisplayName(), summary.getOwnerName());
        assertEquals("open", summary.getStatus());
    }

    @Test
    void getDealSummary_throwsWhenDealMissing() {
        ResourceNotFoundException failure = assertThrows(
            ResourceNotFoundException.class,
            () -> dealService.getDealSummary(-1));

        assertEquals("Deal not found", failure.getMessage());
    }

    @Test
    void move_reordersWithinStageContiguously() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal d1 = newDeal(pipeline, stage, company);
        Deal d2 = newDeal(pipeline, stage, company);
        Deal d3 = newDeal(pipeline, stage, company);

        dealService.move(d3.getId(), stage.getId(), 0);

        List<Deal> column = dealService.getDealsByStageId(stage.getId());
        assertEquals(List.of(d3.getId(), d1.getId(), d2.getId()),
            column.stream().map(Deal::getId).toList());
        assertEquals(List.of(0, 1, 2), column.stream().map(Deal::getPosition).toList());
    }

    @Test
    void move_acrossStages_updatesStageAndRenumbersBothColumns() {
        Pipeline pipeline = newPipeline();
        Stage from = newStage(pipeline, 0);
        Stage to = newStage(pipeline, 1);
        Company company = newCompany();
        Deal d1 = newDeal(pipeline, from, company);
        Deal d2 = newDeal(pipeline, from, company);
        Deal d3 = newDeal(pipeline, from, company);
        Deal d4 = newDeal(pipeline, to, company);
        Deal d5 = newDeal(pipeline, to, company);

        dealService.move(d1.getId(), to.getId(), 1);

        List<Deal> target = dealService.getDealsByStageId(to.getId());
        assertEquals(List.of(d4.getId(), d1.getId(), d5.getId()),
            target.stream().map(Deal::getId).toList());
        assertEquals(List.of(0, 1, 2), target.stream().map(Deal::getPosition).toList());

        List<Deal> source = dealService.getDealsByStageId(from.getId());
        assertEquals(List.of(d2.getId(), d3.getId()), source.stream().map(Deal::getId).toList());
        assertEquals(List.of(0, 1), source.stream().map(Deal::getPosition).toList());
    }

    @Test
    void move_onlySourceDealIntoEmptyStageSkipsEmptySourceBatch() {
        Pipeline pipeline = newPipeline();
        Stage from = newStage(pipeline, 0);
        Stage to = newStage(pipeline, 1);
        Deal deal = newDeal(pipeline, from, newCompany());

        Deal moved = dealService.move(deal.getId(), to.getId(), 0);

        assertEquals(to.getId(), moved.getStageId());
        assertEquals(0, moved.getPosition());
        assertTrue(dealService.getDealsByStageId(from.getId()).isEmpty());
    }

    @Test
    void move_rejectsStageInAnotherPipeline() {
        Pipeline pipelineA = newPipeline();
        Stage stageA = newStage(pipelineA, 0);
        Pipeline pipelineB = newPipeline();
        Stage stageB = newStage(pipelineB, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipelineA, stageA, company);

        assertThrows(BadRequestException.class,
            () -> dealService.move(deal.getId(), stageB.getId(), 0));
    }

    @Test
    void move_throwsWhenDealMissing() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        assertThrows(ResourceNotFoundException.class, () -> dealService.move(-1, stage.getId(), 0));
    }

    @Test
    void update_changingStage_appendsToNewStageTailWithoutCollision() {
        Pipeline pipeline = newPipeline();
        Stage from = newStage(pipeline, 0);
        Stage to = newStage(pipeline, 1);
        Company company = newCompany();
        Deal a = newDeal(pipeline, to, company);
        Deal b = newDeal(pipeline, to, company);
        Deal moved = newDeal(pipeline, from, company);
        dealService.move(a.getId(), to.getId(), 0);
        dealService.move(b.getId(), to.getId(), 1);

        moved.setStageId(to.getId());
        dealService.update(moved.getId(), moved);

        List<Deal> target = dealService.getDealsByStageId(to.getId());
        assertEquals(List.of(a.getId(), b.getId(), moved.getId()),
            target.stream().map(Deal::getId).toList());
        assertEquals(List.of(0, 1, 2), target.stream().map(Deal::getPosition).toList());
    }

    @Test
    void create_open_recordsConversionEligibleInitialHistory() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();

        Deal deal = new Deal();
        deal.setName("Deal " + unique());
        deal.setWorkspaceId(workspace.getId());
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        Deal created = dealService.create(deal);

        List<DealStageHistory> history = dealService.getStageHistory(created.getId());
        assertEquals(1, history.size());
        assertEquals(stage.getId(), history.get(0).getStageId());
        assertTrue(history.get(0).isConversionEligible());
    }

    @Test
    void create_closedAtIngest_recordsConversionIneligibleInitialHistory() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        for (boolean won : new boolean[] {true, false}) {
            Deal deal = new Deal();
            deal.setName("Deal " + unique());
            deal.setWorkspaceId(workspace.getId());
            deal.setValue(new BigDecimal("1000.00"));
            deal.setCurrency("JPY");
            deal.setPipelineId(pipeline.getId());
            deal.setStageId(stage.getId());
            deal.setCompanyId(newCompany().getId());
            deal.setWon(won);
            Deal created = dealService.create(deal);

            List<DealStageHistory> history = dealService.getStageHistory(created.getId());
            assertEquals(1, history.size());
            assertFalse(history.get(0).isConversionEligible(),
                "a deal created already " + (won ? "won" : "lost") + " never occupied the stage while open");
        }
    }

    @Test
    void reviewedCreateRejectsMissingAndMismatchedTokensWithoutAnyWriteOrPublication() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal missingTokenDraft = dealDraft(pipeline, stage, company);
        Deal duplicate = dealDraft(pipeline, stage, company);
        duplicate.setWorkspaceId(workspace.getId());
        duplicate.setOwnerId(currentUser.getId());
        duplicate.setName(missingTokenDraft.getName());
        dealMapper.insert(duplicate);
        MutationFootprint beforeMissing = mutationFootprint();
        clearDealCreationInvocations();

        assertThrows(
            ConflictException.class,
            () -> dealService.createReviewed(missingTokenDraft, null));

        assertRejectedDealCreation(beforeMissing);

        Deal reviewedDraft = dealDraft(pipeline, stage, company);
        reviewedDraft.setName(missingTokenDraft.getName());
        DuplicatePreflightResponse reviewed = duplicatePreflightService.preflightDeal(
            new DealDuplicatePreflightRequest(
                reviewedDraft.getName(),
                reviewedDraft.getCompanyId(),
                null));
        assertEquals(
            List.of(duplicate.getId()),
            reviewed.candidates().stream().map(candidate -> candidate.recordId()).toList());
        reviewedDraft.setName(reviewedDraft.getName().toUpperCase(Locale.ROOT));
        MutationFootprint beforeMismatch = mutationFootprint();
        clearDealCreationInvocations();

        assertThrows(
            ConflictException.class,
            () -> dealService.createReviewed(reviewedDraft, reviewed.reviewToken()));

        assertRejectedDealCreation(beforeMismatch);
    }

    @Test
    void reviewedCreateRejectsCandidateSetChangedBetweenAcknowledgementAndSubmit() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal draft = dealDraft(pipeline, stage, company);
        Deal firstCandidate = dealDraft(pipeline, stage, company);
        firstCandidate.setWorkspaceId(workspace.getId());
        firstCandidate.setOwnerId(currentUser.getId());
        firstCandidate.setName(draft.getName());
        dealMapper.insert(firstCandidate);
        DuplicatePreflightResponse reviewed = duplicatePreflightService.preflightDeal(
            new DealDuplicatePreflightRequest(
                draft.getName(),
                draft.getCompanyId(),
                null));
        assertEquals(
            List.of(firstCandidate.getId()),
            reviewed.candidates().stream().map(candidate -> candidate.recordId()).toList());
        Deal secondCandidate = dealDraft(pipeline, stage, company);
        secondCandidate.setWorkspaceId(workspace.getId());
        secondCandidate.setOwnerId(currentUser.getId());
        secondCandidate.setName(draft.getName());
        dealMapper.insert(secondCandidate);
        DuplicatePreflightResponse submitRecheck = duplicatePreflightService.preflightDeal(
            new DealDuplicatePreflightRequest(
                draft.getName(),
                draft.getCompanyId(),
                reviewed.reviewToken()));
        assertNotEquals(reviewed.reviewToken(), submitRecheck.reviewToken());
        assertEquals(
            List.of(firstCandidate.getId(), secondCandidate.getId()),
            submitRecheck.candidates().stream().map(candidate -> candidate.recordId()).toList());
        MutationFootprint beforeCreate = mutationFootprint();
        clearDealCreationInvocations();

        assertThrows(
            ConflictException.class,
            () -> dealService.createReviewed(draft, reviewed.reviewToken()));

        assertRejectedDealCreation(beforeCreate);
    }

    @Test
    void reviewedCreateAcceptsAcknowledgedExactDuplicateAfterSubmitRecheck() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal draft = dealDraft(pipeline, stage, company);
        String expectedName = draft.getName();
        Deal existingCandidate = dealDraft(pipeline, stage, company);
        existingCandidate.setWorkspaceId(workspace.getId());
        existingCandidate.setOwnerId(currentUser.getId());
        existingCandidate.setName(expectedName);
        dealMapper.insert(existingCandidate);
        DuplicatePreflightResponse reviewed = duplicatePreflightService.preflightDeal(
            new DealDuplicatePreflightRequest(
                draft.getName(),
                draft.getCompanyId(),
                null));
        assertEquals(
            List.of(existingCandidate.getId()),
            reviewed.candidates().stream().map(candidate -> candidate.recordId()).toList());
        DuplicatePreflightResponse submitRecheck = duplicatePreflightService.preflightDeal(
            new DealDuplicatePreflightRequest(
                draft.getName(),
                draft.getCompanyId(),
                reviewed.reviewToken()));
        assertEquals(reviewed.reviewToken(), submitRecheck.reviewToken());

        Deal created = dealService.createReviewed(draft, submitRecheck.reviewToken());

        Deal stored = dealMapper.getDealById(workspace.getId(), created.getId());
        assertEquals(expectedName, stored.getName());
        assertEquals(company.getId(), stored.getCompanyId());
        assertEquals("manual", stored.getValueSource());
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM deal_stage_history WHERE workspace_id = ? AND deal_id = ?",
            Integer.class,
            workspace.getId(),
            created.getId()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE workspace_id = ? AND action = 'deal.create' AND entity_id = ?",
            Integer.class,
            workspace.getId(),
            created.getId()));

        MutationFootprint beforeReuse = mutationFootprint();
        clearDealCreationInvocations();
        Deal reusedDraft = dealDraft(pipeline, stage, company);
        reusedDraft.setName(expectedName);

        assertThrows(
            ConflictException.class,
            () -> dealService.createReviewed(reusedDraft, reviewed.reviewToken()));

        assertRejectedDealCreation(beforeReuse);
    }

    @Test
    void create_rejectsForeignRelatedRecords() {
        Workspace foreignWorkspace = newWorkspace();
        Pipeline foreignPipeline = newPipelineIn(foreignWorkspace);
        Stage foreignStage = newStageIn(foreignWorkspace, foreignPipeline);
        Company foreignCompany = newCompanyIn(foreignWorkspace);
        Deal draft = dealDraft(foreignPipeline, foreignStage, foreignCompany);

        assertThrows(BadRequestException.class, () -> dealService.create(draft));
    }

    @Test
    void create_rejectsStageOutsideSelectedPipeline() {
        Pipeline selectedPipeline = newPipeline();
        Pipeline otherPipeline = newPipeline();
        Stage otherStage = newStage(otherPipeline, 0);
        Deal draft = dealDraft(selectedPipeline, otherStage, newCompany());

        assertThrows(BadRequestException.class, () -> dealService.create(draft));
    }

    @Test
    void update_rejectsForeignCompanyWithoutMutatingDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company originalCompany = newCompany();
        Deal deal = newDeal(pipeline, stage, originalCompany);
        Workspace foreignWorkspace = newWorkspace();
        deal.setCompanyId(newCompanyIn(foreignWorkspace).getId());

        assertThrows(BadRequestException.class, () -> dealService.update(deal.getId(), deal));

        assertEquals(originalCompany.getId(), dealMapper.getDealById(workspace.getId(), deal.getId()).getCompanyId());
    }

    @Test
    void updateNameChangesOnlyNameAndRemainsAvailableWithLineItems() throws Exception {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        deal.setValue(new BigDecimal("125000.00"));
        deal.setActualValue(new BigDecimal("75000.00"));
        deal.setExpectedCloseDate("2027-03-31");
        deal.setWon(true);
        deal.setClosedAt("2026-07-01 12:00:00");
        deal.setClosedReason("Signed");
        dealMapper.update(deal);
        dealMapper.updateValueAndSource(
            workspace.getId(), deal.getId(), deal.getValue(), "manual");
        dealMapper.updateActualValue(workspace.getId(), deal.getId(), deal.getActualValue());
        addLineItem(deal);
        Deal before = dealMapper.getDealById(workspace.getId(), deal.getId());

        long valueChangedBefore = dealEventCount(deal.getId(), "deal.value_changed");

        Deal updated = dealService.updateName(deal.getId(), "FY27 Renewal");

        assertEquals("FY27 Renewal", updated.getName());
        assertEquals(0, before.getValue().compareTo(updated.getValue()));
        assertEquals(0, before.getActualValue().compareTo(updated.getActualValue()));
        assertEquals(before.getCurrency(), updated.getCurrency());
        assertEquals(before.getPipelineId(), updated.getPipelineId());
        assertEquals(before.getStageId(), updated.getStageId());
        assertEquals(before.getPosition(), updated.getPosition());
        assertEquals(before.getOwnerId(), updated.getOwnerId());
        assertEquals(before.getCompanyId(), updated.getCompanyId());
        assertEquals(before.getExpectedCloseDate(), updated.getExpectedCloseDate());
        assertEquals(before.getClosedAt(), updated.getClosedAt());
        assertEquals(before.getClosedReason(), updated.getClosedReason());
        assertEquals(before.getWon(), updated.getWon());
        JsonNode changes = auditChanges(deal.getId(), "deal.update");
        assertEquals(1, changes.size());
        assertEquals(before.getName(), changes.path("name").path("old").asText());
        assertEquals("FY27 Renewal", changes.path("name").path("new").asText());
        assertTrue(hasDealEvent(deal.getId(), "deal.updated"));
        assertEquals(valueChangedBefore, dealEventCount(deal.getId(), "deal.value_changed"));
    }

    @Test
    void updateNameNoOpSkipsWriteAuditAndEvents() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        long auditCount = dealUpdateAuditCount(deal.getId());
        clearInvocations(dealMapperSpy);
        applicationEvents.clear();

        Deal updated = dealService.updateName(deal.getId(), deal.getName());

        assertEquals(deal.getName(), updated.getName());
        assertEquals(auditCount, dealUpdateAuditCount(deal.getId()));
        assertEquals(0, applicationEvents.stream().count());
        verify(dealMapperSpy, never()).updateName(
            anyInt(), anyInt(), any(String.class), any(String.class));
    }

    @Test
    void updateValueChangesOnlyValueAndPublishesExistingValueEvent() throws Exception {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        deal.setActualValue(new BigDecimal("500.00"));
        deal.setExpectedCloseDate("2027-03-31");
        dealMapper.update(deal);
        dealMapper.updateActualValue(workspace.getId(), deal.getId(), deal.getActualValue());
        Deal before = dealMapper.getDealById(workspace.getId(), deal.getId());

        Deal updated = dealService.updateValue(deal.getId(), new BigDecimal("125000.00"));

        assertEquals(0, new BigDecimal("125000.00").compareTo(updated.getValue()));
        assertEquals(before.getName(), updated.getName());
        assertEquals(0, before.getActualValue().compareTo(updated.getActualValue()));
        assertEquals(before.getCurrency(), updated.getCurrency());
        assertEquals(before.getPipelineId(), updated.getPipelineId());
        assertEquals(before.getStageId(), updated.getStageId());
        assertEquals(before.getPosition(), updated.getPosition());
        assertEquals(before.getOwnerId(), updated.getOwnerId());
        assertEquals(before.getCompanyId(), updated.getCompanyId());
        assertEquals(before.getExpectedCloseDate(), updated.getExpectedCloseDate());
        assertEquals(before.getClosedAt(), updated.getClosedAt());
        assertEquals(before.getClosedReason(), updated.getClosedReason());
        assertEquals(before.getWon(), updated.getWon());
        JsonNode changes = auditChanges(deal.getId(), "deal.update");
        assertEquals(1, changes.size());
        assertEquals(before.getValue().doubleValue(), changes.path("value").path("old").asDouble());
        assertEquals(125000.0, changes.path("value").path("new").asDouble());
        assertTrue(hasDealEvent(deal.getId(), "deal.updated"));
        assertTrue(hasDealEvent(deal.getId(), "deal.value_changed"));
    }

    @Test
    void updateValueNoOpSkipsWriteAuditAndEvents() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        long auditCount = dealUpdateAuditCount(deal.getId());
        clearInvocations(dealMapperSpy);
        applicationEvents.clear();

        Deal updated = dealService.updateValue(deal.getId(), new BigDecimal("1000.00"));

        assertEquals(0, new BigDecimal("1000.00").compareTo(updated.getValue()));
        assertEquals(auditCount, dealUpdateAuditCount(deal.getId()));
        assertEquals(0, applicationEvents.stream().count());
        verify(dealMapperSpy, never()).updateValueAndSource(
            anyInt(), anyInt(), any(BigDecimal.class), any(String.class));
    }

    @Test
    void updateValueNoOpRemainsAvailableWithLineItems() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        addLineItem(deal);
        long auditCount = dealUpdateAuditCount(deal.getId());
        clearInvocations(dealMapperSpy);
        applicationEvents.clear();

        Deal updated = dealService.updateValue(deal.getId(), new BigDecimal("25.00"));

        assertEquals(0, new BigDecimal("25.00").compareTo(updated.getValue()));
        assertEquals(auditCount, dealUpdateAuditCount(deal.getId()));
        assertEquals(0, applicationEvents.stream().count());
        verify(dealMapperSpy, never()).updateValueAndSource(
            anyInt(), anyInt(), any(BigDecimal.class), any(String.class));
    }

    @Test
    void updateValueRejectsDealsWithLineItemsAndPreservesValue() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        addLineItem(deal);

        long valueChangedBefore = dealEventCount(deal.getId(), "deal.value_changed");

        ConflictException exception = assertThrows(ConflictException.class,
            () -> dealService.updateValue(deal.getId(), new BigDecimal("125000.00")));

        assertEquals(
            "Cannot manually edit the deal value while line items exist; update or remove the line items first",
            exception.getMessage());
        BigDecimal storedValue = jdbcTemplate.queryForObject(
            "SELECT value FROM deal WHERE workspace_id = ? AND id = ?",
            BigDecimal.class, workspace.getId(), deal.getId());
        assertEquals(0, new BigDecimal("25.00").compareTo(storedValue));
        assertEquals(valueChangedBefore, dealEventCount(deal.getId(), "deal.value_changed"));
    }

    @Test
    void legacyUpdateRejectsChangedValueWhenLineItemsExist() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        addLineItem(deal);
        Deal edit = dealMapper.getDealById(workspace.getId(), deal.getId());
        edit.setValue(new BigDecimal("125000.00"));

        assertThrows(ConflictException.class, () -> dealService.update(deal.getId(), edit));

        BigDecimal storedValue = jdbcTemplate.queryForObject(
            "SELECT value FROM deal WHERE workspace_id = ? AND id = ?",
            BigDecimal.class, workspace.getId(), deal.getId());
        assertEquals(0, new BigDecimal("25.00").compareTo(storedValue));
    }

    @Test
    void legacyUpdateTreatsScaleEquivalentValueAsUnchanged() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        dealMapper.updateValueAndSource(
            workspace.getId(), deal.getId(), new BigDecimal("125000.0"), "manual");
        addLineItem(deal, "125000.00");
        Deal edit = dealMapper.getDealById(workspace.getId(), deal.getId());
        edit.setName("Scale-insensitive renewal");
        edit.setValue(new BigDecimal("125000.00"));

        Deal updated = dealService.update(deal.getId(), edit);

        assertEquals("Scale-insensitive renewal", updated.getName());
        assertEquals(0, new BigDecimal("125000.00").compareTo(updated.getValue()));
        assertFalse(hasDealEvent(deal.getId(), "deal.value_changed"));
    }

    @Test
    void targetedUpdatesReturnNotFoundForMissingAndForeignDeals() {
        assertThrows(ResourceNotFoundException.class, () -> dealService.updateName(-1, "Missing"));
        assertThrows(ResourceNotFoundException.class,
            () -> dealService.updateValue(-1, new BigDecimal("1.00")));

        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal local = newDeal(pipeline, stage, newCompany());
        String originalName = local.getName();
        BigDecimal originalValue = local.getValue();
        Workspace foreignWorkspace = newWorkspace();
        workspaceMapper.addMember(foreignWorkspace.getId(), currentUser.getId(), "owner");
        authenticateAs(currentUser, foreignWorkspace.getId());

        assertThrows(ResourceNotFoundException.class,
            () -> dealService.updateName(local.getId(), "Foreign rename"));
        assertThrows(ResourceNotFoundException.class,
            () -> dealService.updateValue(local.getId(), new BigDecimal("999.00")));

        authenticateAs(currentUser, workspace.getId());
        Deal unchanged = dealMapper.getDealById(workspace.getId(), local.getId());
        assertEquals(originalName, unchanged.getName());
        assertEquals(0, originalValue.compareTo(unchanged.getValue()));
    }

    @Test
    void move_acrossStages_recordsStageHistory() {
        Pipeline pipeline = newPipeline();
        Stage from = newStage(pipeline, 0);
        Stage to = newStage(pipeline, 1);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, from, company);

        dealService.move(deal.getId(), to.getId(), 0);

        List<DealStageHistory> history = dealService.getStageHistory(deal.getId());
        assertEquals(1, history.size());
        assertEquals(to.getId(), history.get(0).getStageId());
    }

    @Test
    void move_withinSameStage_doesNotRecordStageHistory() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal a = newDeal(pipeline, stage, company);
        Deal b = newDeal(pipeline, stage, company);

        dealService.move(b.getId(), stage.getId(), 0);

        assertTrue(dealService.getStageHistory(b.getId()).isEmpty());
    }

    @Test
    void update_changingStage_recordsStageHistory() {
        Pipeline pipeline = newPipeline();
        Stage from = newStage(pipeline, 0);
        Stage to = newStage(pipeline, 1);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, from, company);

        deal.setStageId(to.getId());
        dealService.update(deal.getId(), deal);

        List<DealStageHistory> history = dealService.getStageHistory(deal.getId());
        assertEquals(1, history.size());
        assertEquals(to.getId(), history.get(0).getStageId());
    }

    @Test
    void reopen_fromTerminalStage_recordsReturnStageHistory() throws Exception {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage won = new Stage();
        won.setName("Won " + unique());
        won.setPipeline(pipeline);
        won.setPosition(1);
        won.setWorkspaceId(workspace.getId());
        won.setSuccess(true);
        pipelineMapper.insertStage(won);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, open, company);

        dealService.move(deal.getId(), won.getId(), 0);
        dealService.reopen(deal.getId());

        List<DealStageHistory> history = dealService.getStageHistory(deal.getId());
        assertEquals(2, history.size());
        assertEquals(won.getId(), history.get(0).getStageId());
        assertTrue(history.get(0).isConversionEligible());
        assertEquals(open.getId(), history.get(1).getStageId());
        assertTrue(history.get(1).isConversionEligible());
        assertEquals(open.getId(), dealService.getDealById(deal.getId()).getStageId());

        JsonNode moveChanges = auditChanges(deal.getId(), "deal.update");
        assertEquals(open.getId(), moveChanges.path("stageId").path("old").asInt());
        assertEquals(won.getId(), moveChanges.path("stageId").path("new").asInt());
        assertTrue(moveChanges.has("won"));
        assertTrue(moveChanges.path("won").path("new").asBoolean());

        JsonNode reopenChanges = auditChanges(deal.getId(), "deal.reopen");
        assertEquals(won.getId(), reopenChanges.path("stageId").path("old").asInt());
        assertEquals(open.getId(), reopenChanges.path("stageId").path("new").asInt());
        assertTrue(reopenChanges.has("won"));
        assertTrue(reopenChanges.path("won").path("old").asBoolean());
    }

    @Test
    void reopen_closedDealInNormalStage_restoresConversionEligibility() throws Exception {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage postClose = newStage(pipeline, 1);
        Stage won = new Stage();
        won.setName("Won " + unique());
        won.setPipeline(pipeline);
        won.setPosition(2);
        won.setWorkspaceId(workspace.getId());
        won.setSuccess(true);
        pipelineMapper.insertStage(won);
        Deal deal = newDeal(pipeline, open, newCompany());

        dealService.move(deal.getId(), won.getId(), 0);
        dealService.move(deal.getId(), postClose.getId(), 0);
        dealService.reopen(deal.getId());
        dealService.close(deal.getId(), Boolean.TRUE, null, null);

        List<DealStageHistory> history = dealService.getStageHistory(deal.getId());
        assertEquals(3, history.size());
        assertTrue(history.get(0).isConversionEligible());
        assertFalse(history.get(1).isConversionEligible());
        assertEquals(postClose.getId(), history.get(2).getStageId());
        assertTrue(history.get(2).isConversionEligible());
        assertEquals(Boolean.TRUE, dealService.getDealById(deal.getId()).getWon());

        JsonNode closeChanges = auditChanges(deal.getId(), "deal.close");
        assertTrue(closeChanges.has("won"));
        assertTrue(closeChanges.path("won").path("new").asBoolean());
        assertTrue(closeChanges.path("closedAt").path("new").isTextual());
    }

    @Test
    void update_reopeningInSameStage_recordsEligibleStageHistory() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        dealService.close(deal.getId(), Boolean.FALSE, null, null);

        deal.setWon(null);
        deal.setClosedAt(null);
        dealService.update(deal.getId(), deal);

        List<DealStageHistory> history = dealService.getStageHistory(deal.getId());
        assertEquals(1, history.size());
        assertEquals(stage.getId(), history.get(0).getStageId());
        assertTrue(history.get(0).isConversionEligible());
    }

    @Test
    void getStageHistory_throwsWhenDealMissing() {
        assertThrows(ResourceNotFoundException.class, () -> dealService.getStageHistory(-1));
    }

    @Test
    void reschedule_updatesOnlyExpectedCloseDate() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        String name = deal.getName();

        dealService.reschedule(deal.getId(), "2025-06-30");

        Deal after = dealService.getDealById(deal.getId());
        assertEquals("2025-06-30", after.getExpectedCloseDate());
        assertEquals(name, after.getName());
        assertEquals(0, new BigDecimal("1000.00").compareTo(after.getValue()));
        assertEquals(stage.getId(), after.getStageId());
        assertEquals(pipeline.getId(), after.getPipelineId());
    }

    @Test
    void reschedule_doesNotReopenClosedDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        dealService.close(deal.getId(), Boolean.TRUE, "signed", new BigDecimal("1500.00"));

        dealService.reschedule(deal.getId(), "2025-06-30");

        Deal after = dealService.getDealById(deal.getId());
        assertEquals("2025-06-30", after.getExpectedCloseDate());
        assertEquals(Boolean.TRUE, after.getWon());
        assertNotNull(after.getClosedAt());
    }

    @Test
    void reschedule_throwsWhenDealMissing() {
        assertThrows(ResourceNotFoundException.class, () -> dealService.reschedule(-1, "2025-06-30"));
    }

    @Test
    void reschedule_rejectsInvalidDate() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        assertThrows(BadRequestException.class, () -> dealService.reschedule(deal.getId(), "9999-99-99"));
    }

    @Test
    void closingSoonCountUsesTheActiveWorkspace() {
        Workspace activeWorkspace = newWorkspace();
        workspaceMapper.addMember(activeWorkspace.getId(), currentUser.getId(), "owner");
        workspace = activeWorkspace;
        authenticateAs(currentUser, workspace.getId());

        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal closing = newDeal(pipeline, stage, company);
        Deal later = newDeal(pipeline, stage, company);
        jdbcTemplate.update("UPDATE deal SET expected_close_date = CURDATE() WHERE id = ?", closing.getId());
        jdbcTemplate.update("UPDATE deal SET expected_close_date = DATE_ADD(CURDATE(), INTERVAL 8 DAY) WHERE id = ?",
            later.getId());

        Workspace foreignWorkspace = newWorkspace();
        Pipeline foreignPipeline = newPipelineIn(foreignWorkspace);
        Stage foreignStage = newStageIn(foreignWorkspace, foreignPipeline);
        Company foreignCompany = newCompanyIn(foreignWorkspace);
        Deal foreign = analyticsDeal(foreignWorkspace, foreignPipeline, foreignStage, foreignCompany,
            "Foreign", 100.0, 0.0, "JPY", null, 1, null, 1);
        jdbcTemplate.update("UPDATE deal SET expected_close_date = CURDATE() WHERE id = ?", foreign.getId());

        assertEquals(1, dealService.getClosingSoonCount(7).count());
        assertEquals(List.of(closing.getId()),
            dealService.getClosingSoonDeals(7, 6).stream().map(Deal::getId).toList());
    }

    @Test
    void primaryContactsBatchReturnsOneAlphabeticalVisibleContactPerDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal firstDeal = newDeal(pipeline, stage, newCompany());
        Deal secondDeal = newDeal(pipeline, stage, newCompany());
        Person zulu = personNamed("Zulu Contact");
        Person alpha = personNamed("Alpha Contact");
        Person second = personNamed("Second Contact");
        dealMapper.addPerson(workspace.getId(), firstDeal.getId(), zulu.getId(), null);
        dealMapper.addPerson(workspace.getId(), firstDeal.getId(), alpha.getId(), null);
        dealMapper.addPerson(workspace.getId(), secondDeal.getId(), second.getId(), null);

        List<DealPrimaryContactDto> contacts = dealService.getPrimaryContacts(
            List.of(secondDeal.getId(), firstDeal.getId()));

        assertEquals(2, contacts.size());
        assertEquals(alpha.getId(), contacts.stream()
            .filter(contact -> contact.dealId() == firstDeal.getId())
            .findFirst().orElseThrow().personId());
        assertEquals(second.getId(), contacts.stream()
            .filter(contact -> contact.dealId() == secondDeal.getId())
            .findFirst().orElseThrow().personId());
        assertTrue(dealService.getPrimaryContacts(List.of()).isEmpty());
    }

    @Test
    void sharedPersonCanBeRemovedFromOwnedDealAfterShareRevocation() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        Workspace sibling = new Workspace();
        sibling.setName("Sibling " + unique());
        sibling.setSlug("sibling-" + unique());
        Integer orgId = workspaceMapper.getOrgId(workspace.getId());
        assertNotNull(orgId);
        sibling.setOrgId(orgId);
        workspaceMapper.insert(sibling);
        Person shared = new Person();
        shared.setWorkspaceId(sibling.getId());
        shared.setName("Shared " + unique());
        personMapper.insert(shared);
        assertEquals(1, shareMapper.sharePerson(
            shared.getId(), sibling.getId(), workspace.getId(), currentUser.getId(), false));
        dealService.addPerson(deal.getId(), shared.getId(), "champion");
        assertEquals(1, shareMapper.unsharePerson(
            shared.getId(), sibling.getId(), workspace.getId()));

        dealService.removePerson(deal.getId(), shared.getId());

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM deal_person WHERE deal_id = ? AND person_id = ?",
            Integer.class, deal.getId(), shared.getId()));
    }

    private Workspace newWorkspace() {
        Workspace created = new Workspace();
        created.setName("Workspace " + unique());
        created.setSlug("workspace_" + unique());
        workspaceMapper.insert(created);
        return created;
    }

    private Pipeline newPipelineIn(Workspace targetWorkspace) {
        Pipeline pipeline = new Pipeline();
        pipeline.setName("Pipeline " + unique());
        pipeline.setWorkspaceId(targetWorkspace.getId());
        pipelineMapper.insertPipeline(pipeline);
        return pipeline;
    }

    private Stage newStageIn(Workspace targetWorkspace, Pipeline pipeline) {
        Stage stage = new Stage();
        stage.setName("Stage " + unique());
        stage.setPipeline(pipeline);
        stage.setPosition(0);
        stage.setWorkspaceId(targetWorkspace.getId());
        pipelineMapper.insertStage(stage);
        return stage;
    }

    private Company newCompanyIn(Workspace targetWorkspace) {
        Company company = new Company();
        company.setName("Company " + unique());
        company.setWorkspaceId(targetWorkspace.getId());
        companyMapper.insert(company);
        return company;
    }

    private Person personNamed(String name) {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName(name);
        personMapper.insert(person);
        return person;
    }

    @Test
    void draggingLineItemDealOntoWonStageDerivesRealizedValue() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage won = newSuccessStage(pipeline, 1);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, open, company);
        addLineItem(deal, "5000000.00");

        dealService.move(deal.getId(), won.getId(), 0);

        Deal after = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(Boolean.TRUE, after.getWon());
        assertEquals(0, new BigDecimal("5000000.00").compareTo(after.getActualValue()));
    }

    @Test
    void editingLineItemDealToWonStageDerivesRealizedValue() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage won = newSuccessStage(pipeline, 1);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, open, company);
        addLineItem(deal, "5000000.00");

        Deal edit = dealMapper.getDealById(workspace.getId(), deal.getId());
        edit.setStageId(won.getId());
        dealService.update(deal.getId(), edit);

        Deal after = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(Boolean.TRUE, after.getWon());
        assertEquals(0, new BigDecimal("5000000.00").compareTo(after.getActualValue()));
    }

    @Test
    void everyWinPathPersistsTheSameRealizedValueForALineItemDeal() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage won = newSuccessStage(pipeline, 1);
        Company company = newCompany();

        Deal viaClose = newDeal(pipeline, open, company);
        addLineItem(viaClose, "1234567.00");
        Deal viaMove = newDeal(pipeline, open, company);
        addLineItem(viaMove, "1234567.00");
        Deal viaEdit = newDeal(pipeline, open, company);
        addLineItem(viaEdit, "1234567.00");

        dealService.close(viaClose.getId(), true, "Signed", null);
        dealService.move(viaMove.getId(), won.getId(), 0);
        Deal edit = dealMapper.getDealById(workspace.getId(), viaEdit.getId());
        edit.setStageId(won.getId());
        dealService.update(viaEdit.getId(), edit);

        BigDecimal expected = new BigDecimal("1234567.00");
        assertEquals(0, expected.compareTo(storedActualValue(viaClose.getId())));
        assertEquals(0, expected.compareTo(storedActualValue(viaMove.getId())));
        assertEquals(0, expected.compareTo(storedActualValue(viaEdit.getId())));
    }

    @Test
    void losingLineItemDealByDragRecordsZeroRealizedValue() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage lost = newFailureStage(pipeline, 1);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, open, company);
        addLineItem(deal, "5000000.00");

        dealService.move(deal.getId(), lost.getId(), 0);

        Deal after = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(Boolean.FALSE, after.getWon());
        assertEquals(0, BigDecimal.ZERO.compareTo(after.getActualValue()));
    }

    @Test
    void losingAWonDealByDragRecordsZeroRealizedValue() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage lost = newFailureStage(pipeline, 1);
        Deal deal = wonLineItemDeal(pipeline, open, newCompany());

        dealService.move(deal.getId(), lost.getId(), 0);

        Deal after = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(Boolean.FALSE, after.getWon());
        assertEquals(0, BigDecimal.ZERO.compareTo(after.getActualValue()));
    }

    @Test
    void losingAWonDealByEditIgnoresTheSubmittedRealizedValue() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage lost = newFailureStage(pipeline, 1);
        Deal deal = wonLineItemDeal(pipeline, open, newCompany());

        Deal edit = dealMapper.getDealById(workspace.getId(), deal.getId());
        edit.setStageId(lost.getId());
        edit.setActualValue(new BigDecimal("999999.00"));
        dealService.update(deal.getId(), edit);

        Deal after = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(Boolean.FALSE, after.getWon());
        assertEquals(0, BigDecimal.ZERO.compareTo(after.getActualValue()));
    }

    @Test
    void losingADealThroughTheCloseDialogIgnoresTheSubmittedRealizedValue() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Deal deal = wonLineItemDeal(pipeline, open, newCompany());

        dealService.close(deal.getId(), false, "Lost to a competitor", new BigDecimal("999999.00"));

        Deal after = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(Boolean.FALSE, after.getWon());
        assertEquals(0, BigDecimal.ZERO.compareTo(after.getActualValue()));
    }

    @Test
    void losingAWonDealByBulkStageChangeRecordsZeroRealizedValue() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage lost = newFailureStage(pipeline, 1);
        Deal deal = wonLineItemDeal(pipeline, open, newCompany());

        bulkOperationService.changeStageForDeals(List.of(deal.getId()), lost.getId());

        Deal after = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(Boolean.FALSE, after.getWon());
        assertEquals(0, BigDecimal.ZERO.compareTo(after.getActualValue()));
    }

    @Test
    void losingAWonDealByRuleActionRecordsZeroRealizedValue() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage lost = newFailureStage(pipeline, 1);
        Deal deal = wonLineItemDeal(pipeline, open, newCompany());

        ruleActionExecutor.execute(changeStageAction(lost.getId()), ruleFire(deal.getId()));

        Deal after = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(Boolean.FALSE, after.getWon());
        assertEquals(0, BigDecimal.ZERO.compareTo(after.getActualValue()));
    }

    @Test
    void everyLosePathRecordsZeroRealizedValueForAPreviouslyWonDeal() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage lost = newFailureStage(pipeline, 1);
        Company company = newCompany();

        Deal viaClose = wonLineItemDeal(pipeline, open, company);
        Deal viaMove = wonLineItemDeal(pipeline, open, company);
        Deal viaEdit = wonLineItemDeal(pipeline, open, company);
        Deal viaBulk = wonLineItemDeal(pipeline, open, company);
        Deal viaRule = wonLineItemDeal(pipeline, open, company);

        dealService.close(viaClose.getId(), false, "Lost to a competitor", null);
        dealService.move(viaMove.getId(), lost.getId(), 0);
        Deal edit = dealMapper.getDealById(workspace.getId(), viaEdit.getId());
        edit.setStageId(lost.getId());
        dealService.update(viaEdit.getId(), edit);
        bulkOperationService.changeStageForDeals(List.of(viaBulk.getId()), lost.getId());
        ruleActionExecutor.execute(changeStageAction(lost.getId()), ruleFire(viaRule.getId()));

        for (Deal deal : List.of(viaClose, viaMove, viaEdit, viaBulk, viaRule)) {
            Deal after = dealMapper.getDealById(workspace.getId(), deal.getId());
            assertEquals(Boolean.FALSE, after.getWon(), "deal " + deal.getId() + " is not lost");
            assertEquals(0, BigDecimal.ZERO.compareTo(after.getActualValue()),
                "deal " + deal.getId() + " kept realized value " + after.getActualValue());
        }
    }

    @Test
    void creatingADealOnALostStageRecordsZeroRealizedValue() {
        Pipeline pipeline = newPipeline();
        Stage lost = newFailureStage(pipeline, 0);
        Deal draft = dealDraft(pipeline, lost, newCompany());
        draft.setActualValue(new BigDecimal("999999.00"));

        Deal created = dealService.create(draft);

        Deal after = dealMapper.getDealById(workspace.getId(), created.getId());
        assertEquals(Boolean.FALSE, after.getWon());
        assertEquals(0, BigDecimal.ZERO.compareTo(after.getActualValue()));
    }

    @Test
    void reWinningAfterALossDerivesTheSameRealizedValueAsTheFirstWin() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage lost = newFailureStage(pipeline, 1);
        Stage won = newSuccessStage(pipeline, 2);
        Deal deal = wonLineItemDeal(pipeline, open, newCompany());
        BigDecimal firstWin = storedActualValue(deal.getId());

        dealService.move(deal.getId(), lost.getId(), 0);
        assertEquals(0, BigDecimal.ZERO.compareTo(storedActualValue(deal.getId())));
        dealService.move(deal.getId(), won.getId(), 0);

        Deal after = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(Boolean.TRUE, after.getWon());
        assertEquals(0, new BigDecimal("5000000.00").compareTo(firstWin));
        assertEquals(0, firstWin.compareTo(after.getActualValue()));
    }

    @Test
    void reWinningAfterAReopenDerivesTheSameRealizedValueAsTheFirstWin() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage won = newSuccessStage(pipeline, 1);
        Deal deal = wonLineItemDeal(pipeline, open, newCompany());
        BigDecimal firstWin = storedActualValue(deal.getId());

        dealService.reopen(deal.getId());
        dealService.move(deal.getId(), won.getId(), 0);

        Deal after = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(Boolean.TRUE, after.getWon());
        assertEquals(0, firstWin.compareTo(after.getActualValue()));
    }

    @Test
    void reWinningAManualDealAfterALossDoesNotResurrectTheOldFigure() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage lost = newFailureStage(pipeline, 1);
        Stage won = newSuccessStage(pipeline, 2);
        Deal deal = newDeal(pipeline, open, newCompany());
        dealService.close(deal.getId(), true, "Signed", new BigDecimal("5000000.00"));
        assertEquals(0, new BigDecimal("5000000.00").compareTo(storedActualValue(deal.getId())));

        dealService.move(deal.getId(), lost.getId(), 0);
        dealService.move(deal.getId(), won.getId(), 0);

        Deal after = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals(Boolean.TRUE, after.getWon());
        assertEquals(0, BigDecimal.ZERO.compareTo(after.getActualValue()));
    }

    @Test
    void reWinningAgreesWhicheverWayTheDealWasUnwon() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage lost = newFailureStage(pipeline, 1);
        Stage won = newSuccessStage(pipeline, 2);
        Company company = newCompany();

        Deal viaLost = newDeal(pipeline, open, company);
        dealService.close(viaLost.getId(), true, "Signed", new BigDecimal("5000000.00"));
        dealService.move(viaLost.getId(), lost.getId(), 0);
        dealService.move(viaLost.getId(), won.getId(), 0);

        Deal viaReopen = newDeal(pipeline, open, company);
        dealService.close(viaReopen.getId(), true, "Signed", new BigDecimal("5000000.00"));
        dealService.reopen(viaReopen.getId());
        dealService.move(viaReopen.getId(), won.getId(), 0);

        assertEquals(0, storedActualValue(viaLost.getId())
            .compareTo(storedActualValue(viaReopen.getId())));
    }

    private Deal wonLineItemDeal(Pipeline pipeline, Stage stage, Company company) {
        Deal deal = newDeal(pipeline, stage, company);
        addLineItem(deal, "5000000.00");
        dealService.close(deal.getId(), true, "Signed", null);
        return deal;
    }

    private static RuleAction changeStageAction(int stageId) {
        RuleAction action = new RuleAction();
        action.setType("change_stage");
        action.setTargetStageId(stageId);
        return action;
    }

    private RuleFireContext ruleFire(int dealId) {
        return new RuleFireContext(
            workspace.getId(), 0, "deal", dealId, currentUser.getId(), "lose-path");
    }

    private Stage newSuccessStage(Pipeline pipeline, int position) {
        Stage stage = new Stage();
        stage.setName("Won " + unique());
        stage.setPipeline(pipeline);
        stage.setPosition(position);
        stage.setSuccess(true);
        stage.setWorkspaceId(workspace.getId());
        pipelineMapper.insertStage(stage);
        return stage;
    }

    private Stage newFailureStage(Pipeline pipeline, int position) {
        Stage stage = new Stage();
        stage.setName("Lost " + unique());
        stage.setPipeline(pipeline);
        stage.setPosition(position);
        stage.setFailure(true);
        stage.setWorkspaceId(workspace.getId());
        pipelineMapper.insertStage(stage);
        return stage;
    }

    private BigDecimal storedActualValue(int dealId) {
        return dealMapper.getDealById(workspace.getId(), dealId).getActualValue();
    }

    private Deal dealDraft(Pipeline pipeline, Stage stage, Company company) {
        Deal deal = new Deal();
        deal.setName("Deal " + unique());
        deal.setValue(new BigDecimal("1000.00"));
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        return deal;
    }

    private void addLineItem(Deal deal) {
        addLineItem(deal, "25.00");
    }

    private void addLineItem(Deal deal, String unitPrice) {
        DealLineItemRequest request = new DealLineItemRequest();
        request.setName("Ad-hoc line " + unique());
        request.setUnitPrice(new BigDecimal(unitPrice));
        request.setQuantity(BigDecimal.ONE);
        dealLineItemService.create(deal.getId(), request);
    }

    private void clearDealCreationInvocations() {
        clearInvocations(dealMapperSpy, notificationChanges, ruleTriggers);
    }

    private void assertRejectedDealCreation(MutationFootprint before) {
        assertEquals(before, mutationFootprint());
        verify(dealMapperSpy, never()).insert(any(Deal.class));
        verify(notificationChanges, never()).publish(anyInt(), any(), any());
        verify(ruleTriggers, never()).publish(anyInt(), any(), anyInt(), any());
    }

    private MutationFootprint mutationFootprint() {
        return new MutationFootprint(
            count("SELECT COUNT(*) FROM deal WHERE workspace_id = ?"),
            count("SELECT COUNT(*) FROM deal_stage_history WHERE workspace_id = ?"),
            count("SELECT COUNT(*) FROM audit_log WHERE workspace_id = ?"),
            count("SELECT COUNT(*) FROM notification WHERE workspace_id = ?"),
            count("SELECT COUNT(*) FROM entity_reference WHERE workspace_id = ?"),
            count("SELECT COUNT(*) FROM workflow_trigger_outbox WHERE workspace_id = ?"),
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM activity", Integer.class),
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM deal_person", Integer.class));
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class, workspace.getId());
    }

    private long dealEventCount(int dealId, String event) {
        return applicationEvents.stream(RuleTriggerEvent.class)
            .filter(trigger -> trigger.workspaceId() == workspace.getId()
                && trigger.entityId() == dealId
                && "deal".equals(trigger.recordType())
                && event.equals(trigger.event()))
            .count();
    }

    private boolean hasDealEvent(int dealId, String event) {
        return applicationEvents.stream(RuleTriggerEvent.class)
            .anyMatch(trigger -> trigger.workspaceId() == workspace.getId()
                && trigger.entityId() == dealId
                && "deal".equals(trigger.recordType())
                && event.equals(trigger.event()));
    }

    private Deal boardDeal(Pipeline pipeline, Stage stage, int position) {
        Deal deal = new Deal();
        deal.setWorkspaceId(workspace.getId());
        deal.setName("Bounded Board " + position);
        deal.setValue(BigDecimal.ONE);
        deal.setCurrency("USD");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setPosition(position - 1);
        return deal;
    }

    private static SegmentCondition segmentField(String field, String op, String value) {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField(field);
        condition.setOp(op);
        condition.setValue(value);
        return condition;
    }

    private static SegmentDefinition segmentDefinition(SegmentCondition... conditions) {
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(conditions));
        return definition;
    }

    private Deal updateDeal(Deal deal, String name, double value, double actualValue,
            String currency, Boolean won) {
        deal.setName(name);
        deal.setValue(BigDecimal.valueOf(value));
        deal.setActualValue(BigDecimal.valueOf(actualValue));
        deal.setCurrency(currency);
        deal.setWon(won);
        deal.setClosedAt(won == null ? null : "2026-01-01 00:00:00");
        dealMapper.update(deal);
        dealMapper.updateValueAndSource(
            deal.getWorkspaceId(), deal.getId(), deal.getValue(), "manual");
        dealMapper.updateActualValue(deal.getWorkspaceId(), deal.getId(), deal.getActualValue());
        return deal;
    }

    private Deal updateChartDeal(Deal deal, double value, double actualValue, String currency,
            Boolean won, String expectedCloseDate, String closedAt) {
        deal.setValue(BigDecimal.valueOf(value));
        deal.setActualValue(BigDecimal.valueOf(actualValue));
        deal.setCurrency(currency);
        deal.setWon(won);
        deal.setExpectedCloseDate(expectedCloseDate);
        deal.setClosedAt(closedAt);
        dealMapper.update(deal);
        dealMapper.updateValueAndSource(
            deal.getWorkspaceId(), deal.getId(), deal.getValue(), "manual");
        dealMapper.updateActualValue(deal.getWorkspaceId(), deal.getId(), deal.getActualValue());
        return deal;
    }

    private Deal analyticsDeal(Workspace targetWorkspace, Pipeline pipeline, Stage stage, Company company,
            String name, double value, double actualValue, String currency, Boolean won,
            int createdDaysAgo, Integer closedDaysAgo, int updatedDaysAgo) {
        Deal deal = new Deal();
        deal.setWorkspaceId(targetWorkspace.getId());
        deal.setOwnerId(targetWorkspace.getId() == workspace.getId() ? currentUser.getId() : null);
        deal.setName(name);
        deal.setValue(BigDecimal.valueOf(value));
        deal.setActualValue(BigDecimal.valueOf(actualValue));
        deal.setCurrency(currency);
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        deal.setWon(won);
        deal.setClosedAt(won == null ? null : "2000-01-01 00:00:00");
        dealMapper.insert(deal);
        if (won == null) {
            jdbcTemplate.update("""
                UPDATE deal
                SET created_at = DATE_SUB(NOW(), INTERVAL ? DAY),
                    updated_at = DATE_SUB(NOW(), INTERVAL ? DAY)
                WHERE workspace_id = ? AND id = ?
                """, createdDaysAgo, updatedDaysAgo, targetWorkspace.getId(), deal.getId());
        } else {
            if (closedDaysAgo == null) {
                throw new IllegalArgumentException("A closed deal requires a close timestamp");
            }
            jdbcTemplate.update("""
                UPDATE deal
                SET created_at = DATE_SUB(NOW(), INTERVAL ? DAY),
                    closed_at = DATE_SUB(NOW(), INTERVAL ? DAY),
                    updated_at = DATE_SUB(NOW(), INTERVAL ? DAY)
                WHERE workspace_id = ? AND id = ?
                """, createdDaysAgo, closedDaysAgo, updatedDaysAgo, targetWorkspace.getId(), deal.getId());
        }
        return deal;
    }

    private Deal windowDeal(
            Workspace targetWorkspace,
            Pipeline pipeline,
            Stage stage,
            Company company,
            String name,
            double value,
            double actualValue,
            String currency,
            Boolean won,
            LocalDateTime createdAt,
            LocalDateTime closedAt,
            String expectedCloseDate) {
        Deal deal = new Deal();
        deal.setWorkspaceId(targetWorkspace.getId());
        deal.setOwnerId(targetWorkspace.getId() == workspace.getId() ? currentUser.getId() : null);
        deal.setName(name);
        deal.setValue(BigDecimal.valueOf(value));
        deal.setActualValue(BigDecimal.valueOf(actualValue));
        deal.setCurrency(currency);
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        deal.setWon(won);
        deal.setClosedAt(closedAt == null ? null : closedAt.toString().replace('T', ' '));
        deal.setExpectedCloseDate(expectedCloseDate);
        dealMapper.insert(deal);
        jdbcTemplate.update(
            "UPDATE deal SET created_at = ? WHERE workspace_id = ? AND id = ?",
            createdAt, targetWorkspace.getId(), deal.getId());
        return deal;
    }

    private Map<String, Long> facetCounts(List<FacetCount> facets) {
        return facets.stream().collect(Collectors.toMap(FacetCount::getKey, FacetCount::getCount));
    }

    private JsonNode auditChanges(int dealId, String action) throws Exception {
        AuditLog audit = auditService.forEntity("deal", dealId, 20, 0).stream()
            .filter(entry -> action.equals(entry.getAction()))
            .findFirst()
            .orElseThrow();
        assertNotNull(audit.getChanges());
        return objectMapper.readTree(audit.getChanges());
    }

    private long dealUpdateAuditCount(int dealId) {
        return auditService.forEntity("deal", dealId, 20, 0).stream()
            .filter(entry -> "deal.update".equals(entry.getAction()))
            .count();
    }

    private record MutationFootprint(
            int deals,
            int stageHistory,
            int audits,
            int notifications,
            int references,
            int workflowTriggers,
            int activities,
            int relationships) {
    }

    private Map<String, Double> monthTotals(List<DealMonthTotalDto> totals) {
        return totals.stream().collect(Collectors.toMap(
            total -> total.year() + "-" + total.month(), total -> total.total().doubleValue()));
    }
}
