package ooo.klae.connex.backend.mappers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DealAgingDto;
import ooo.klae.connex.backend.dto.DealBucketValueDto;
import ooo.klae.connex.backend.dto.DealCurrencyMetricsDto;
import ooo.klae.connex.backend.dto.DealKpiClosedBucketDto;
import ooo.klae.connex.backend.dto.DealKpiPeriodDto;
import ooo.klae.connex.backend.dto.DealMonthTotalDto;
import ooo.klae.connex.backend.dto.DealPipelineValueDto;
import ooo.klae.connex.backend.dto.DealStageDistributionDto;
import ooo.klae.connex.backend.dto.FacetCount;

class DealMapperTest extends AbstractMapperTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * Inserts a new deal and checks if the generated ID is not zero.
     */
    @Test
    void insert_assignsGeneratedId() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();

        Deal deal = newDeal(pipeline, stage, company);

        // System.out.println("Deal ID: " + deal.getId());
        
        assertNotEquals(0, deal.getId());
    }

    /**
     * Gets a deal by ID and checks if the returned deal is not null.
     */
    @Test
    void getDealById_returnsInsertedRow() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);

        Deal found = dealMapper.getDealById(workspace.getId(), deal.getId());

        assertNotNull(found);
        assertEquals(deal.getName(), found.getName());
        assertEquals(1000.0, found.getValue());
        assertEquals("JPY", found.getCurrency());
        assertEquals(pipeline.getId(), found.getPipelineId());
        assertEquals(stage.getId(), found.getStageId());
        assertEquals(company.getId(), found.getCompanyId());
    }

    /**
     * Gets a deal by ID and checks if the returned deal is null when the ID is negative.
     */
    @Test
    void getDealById_returnsNullWhenMissing() {
        assertNull(dealMapper.getDealById(workspace.getId(), -1));
    }

    /**
     * Gets all deals and checks if the returned list includes the inserted deal.
     */
    @Test
    void getAllDeals_includesInsertedRow() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());

        List<Deal> all = dealMapper.getAllDeals(workspace.getId());

        assertTrue(all.stream().anyMatch(x -> x.getId() == deal.getId()));
    }

    @Test
    void getDealsPageLimitsAndCountsWorkspaceRows() {
        Workspace pageWorkspace = newWorkspace();
        Pipeline pipeline = newPipelineIn(pageWorkspace);
        Stage earlierStage = newStageIn(pageWorkspace, pipeline, 0);
        Stage laterStage = newStageIn(pageWorkspace, pipeline, 1);
        Deal laterStageDeal = newDealIn(pageWorkspace, pipeline, laterStage);
        Deal firstTie = newDealIn(pageWorkspace, pipeline, earlierStage);
        Deal secondTie = newDealIn(pageWorkspace, pipeline, earlierStage);
        Deal afterTies = newDealIn(pageWorkspace, pipeline, earlierStage);
        dealMapper.setPosition(pageWorkspace.getId(), laterStageDeal.getId(), 0);
        dealMapper.setPosition(pageWorkspace.getId(), firstTie.getId(), 0);
        dealMapper.setPosition(pageWorkspace.getId(), secondTie.getId(), 0);
        dealMapper.setPosition(pageWorkspace.getId(), afterTies.getId(), 1);
        Pipeline foreignPipeline = newPipeline();
        Stage foreignStage = newStage(foreignPipeline, 0);
        Deal foreign = newDeal(foreignPipeline, foreignStage, newCompany());

        List<Deal> page = dealMapper.getDealsPage(
            pageWorkspace.getId(), null, null, null, null, null, null, null, null, 3, 0);

        assertEquals(3, page.size());
        assertEquals(4, dealMapper.countDeals(
            pageWorkspace.getId(), null, null, null, null, null, null));
        assertTrue(page.stream().noneMatch(deal -> deal.getId() == foreign.getId()));
        assertEquals(List.of(firstTie.getId(), secondTie.getId(), afterTies.getId()),
            page.stream().map(Deal::getId).toList());
    }

    @Test
    void getDealsPageSortsByRelatedNamesAndStatusWithinWorkspace() {
        Workspace pageWorkspace = newWorkspace();
        Pipeline zuluPipeline = newPipelineIn(pageWorkspace, "Zulu Pipeline");
        Pipeline alphaPipeline = newPipelineIn(pageWorkspace, "Alpha Pipeline");
        Pipeline middlePipeline = newPipelineIn(pageWorkspace, "Middle Pipeline");
        Stage bravoStage = newStageIn(pageWorkspace, zuluPipeline, "Bravo Stage", 0);
        Stage zuluStage = newStageIn(pageWorkspace, alphaPipeline, "Zulu Stage", 0);
        Stage alphaStage = newStageIn(pageWorkspace, middlePipeline, "Alpha Stage", 0);
        Company alphaCompany = newCompanyIn(pageWorkspace, "Alpha Company");
        Company zuluCompany = newCompanyIn(pageWorkspace, "Zulu Company");

        Deal open = newDealIn(pageWorkspace, zuluPipeline, bravoStage);
        open.setCompanyId(alphaCompany.getId());
        updateDeal(open, "Open Deal", 100.0, 0.0, "JPY", null);
        Deal won = newDealIn(pageWorkspace, alphaPipeline, zuluStage);
        won.setCompanyId(zuluCompany.getId());
        updateDeal(won, "Won Deal", 200.0, 180.0, "JPY", true);
        Deal lostWithoutCompany = updateDeal(
            newDealIn(pageWorkspace, middlePipeline, alphaStage),
            "Lost Deal", 50.0, 0.0, "JPY", false);

        Workspace foreignWorkspace = newWorkspace();
        Pipeline foreignPipeline = newPipelineIn(foreignWorkspace, "Aardvark Pipeline");
        Stage foreignStage = newStageIn(foreignWorkspace, foreignPipeline, "Aardvark Stage", 0);
        Deal foreign = newDealIn(foreignWorkspace, foreignPipeline, foreignStage);

        List<Integer> companyAscending = dealPageIds(pageWorkspace, "company", "asc");
        assertEquals(List.of(lostWithoutCompany.getId(), open.getId(), won.getId()), companyAscending);
        assertEquals(List.of(won.getId(), open.getId(), lostWithoutCompany.getId()),
            dealPageIds(pageWorkspace, "company", "desc"));
        assertTrue(companyAscending.contains(lostWithoutCompany.getId()));
        assertFalse(companyAscending.contains(foreign.getId()));

        assertEquals(List.of(won.getId(), lostWithoutCompany.getId(), open.getId()),
            dealPageIds(pageWorkspace, "pipeline", "asc"));
        assertEquals(List.of(open.getId(), lostWithoutCompany.getId(), won.getId()),
            dealPageIds(pageWorkspace, "pipeline", "desc"));
        assertEquals(List.of(lostWithoutCompany.getId(), open.getId(), won.getId()),
            dealPageIds(pageWorkspace, "stage", "asc"));
        assertEquals(List.of(won.getId(), open.getId(), lostWithoutCompany.getId()),
            dealPageIds(pageWorkspace, "stage", "desc"));
        assertEquals(List.of(open.getId(), won.getId(), lostWithoutCompany.getId()),
            dealPageIds(pageWorkspace, "status", "asc"));
        assertEquals(List.of(won.getId(), lostWithoutCompany.getId(), open.getId()),
            dealPageIds(pageWorkspace, "status", "desc"));
    }

    @Test
    void relatedNameReadsIgnoreForeignRowsReferencedByCorruptDeal() {
        Workspace pageWorkspace = newWorkspace();
        Pipeline localPipeline = newPipelineIn(pageWorkspace, "Alpha Pipeline");
        Stage localStage = newStageIn(pageWorkspace, localPipeline, "Alpha Stage", 0);
        Company localCompany = newCompanyIn(pageWorkspace, "Alpha Company");
        Deal local = newDealIn(pageWorkspace, localPipeline, localStage);
        local.setCompanyId(localCompany.getId());
        dealMapper.update(local);

        Deal corrupt = newDealIn(pageWorkspace, localPipeline, localStage);
        Workspace foreignWorkspace = newWorkspace();
        Pipeline foreignPipeline = newPipelineIn(foreignWorkspace, "Zulu Foreign Pipeline");
        Stage foreignStage = newStageIn(foreignWorkspace, foreignPipeline, "Zulu Foreign Stage", 0);
        Company foreignCompany = newCompanyIn(foreignWorkspace, "Zulu Foreign Company");
        jdbcTemplate.update(
            "UPDATE deal SET pipeline_id = ?, stage_id = ?, company_id = ? WHERE id = ?",
            foreignPipeline.getId(), foreignStage.getId(), foreignCompany.getId(), corrupt.getId());

        assertEquals(List.of(corrupt.getId(), local.getId()), dealPageIds(pageWorkspace, "company", "asc"));
        assertEquals(List.of(corrupt.getId(), local.getId()), dealPageIds(pageWorkspace, "pipeline", "asc"));
        assertEquals(List.of(corrupt.getId(), local.getId()), dealPageIds(pageWorkspace, "stage", "asc"));
        assertTrue(dealMapper.search(pageWorkspace.getId(), "%Zulu Foreign%").isEmpty());
    }

    @Test
    void dealMetricsAggregatesMatchingDealsByCurrency() {
        workspace = newWorkspace();
        Pipeline pipeline = newPipeline();
        Stage firstStage = newStage(pipeline, 0);
        Stage secondStage = newStage(pipeline, 1);
        Company company = newCompany();

        updateDeal(newDeal(pipeline, firstStage, company), "Japan Open", 100.0, 0.0, "JPY", null);
        updateDeal(newDeal(pipeline, firstStage, company), "Japan Won", 200.0, 180.0, "JPY", true);
        updateDeal(newDeal(pipeline, firstStage, company), "Japan Lost", 50.0, 0.0, "JPY", false);
        updateDeal(newDeal(pipeline, secondStage, company), "United States Won", 300.0, 250.0, "USD", true);

        List<DealCurrencyMetricsDto> metrics = dealMapper.dealMetrics(
            workspace.getId(), null, null, null, null, null, null);

        DealCurrencyMetricsDto jpy = metricsFor(metrics, "JPY");
        assertEquals(1, jpy.openCount());
        assertEquals(100.0, jpy.openValue(), 0.0001);
        assertEquals(2, jpy.closedCount());
        assertEquals(250.0, jpy.closedForecast(), 0.0001);
        assertEquals(180.0, jpy.closedRevenue(), 0.0001);
        assertEquals(1, jpy.wonCount());
        assertEquals(1, jpy.lostCount());

        DealCurrencyMetricsDto usd = metricsFor(metrics, "USD");
        assertEquals(0, usd.openCount());
        assertEquals(0.0, usd.openValue(), 0.0001);
        assertEquals(1, usd.closedCount());
        assertEquals(300.0, usd.closedForecast(), 0.0001);
        assertEquals(250.0, usd.closedRevenue(), 0.0001);
        assertEquals(1, usd.wonCount());
        assertEquals(0, usd.lostCount());

        List<DealCurrencyMetricsDto> filtered = dealMapper.dealMetrics(
            workspace.getId(), "%Japan%", "JPY", pipeline.getId(), firstStage.getId(), company.getId(), "closed");

        assertEquals(1, filtered.size());
        assertEquals(0, filtered.get(0).openCount());
        assertEquals(2, filtered.get(0).closedCount());
        assertEquals(250.0, filtered.get(0).closedForecast(), 0.0001);
        assertEquals(180.0, filtered.get(0).closedRevenue(), 0.0001);
        assertEquals(1, dealMapper.countDeals(
            workspace.getId(), null, null, null, null, null, "open"));
        assertEquals(3, dealMapper.countDeals(
            workspace.getId(), null, null, null, null, null, "closed"));
        assertEquals(2, dealMapper.countDeals(
            workspace.getId(), null, null, null, null, null, "won"));
        assertEquals(1, dealMapper.countDeals(
            workspace.getId(), null, null, null, null, null, "lost"));
    }

    @Test
    void dealChartAggregationsCoverAllWorkspaceDealsAndApplyCurrencyFilter() {
        workspace = newWorkspace();
        Pipeline firstPipeline = newPipeline();
        Stage firstStage = newStage(firstPipeline, 0);
        Pipeline secondPipeline = newPipeline();
        Stage secondStage = newStage(secondPipeline, 0);
        Company company = newCompany();

        updateChartDeal(newDeal(firstPipeline, firstStage, company),
            100.0, 0.0, "JPY", null, "2026-02-10", null);
        updateChartDeal(newDeal(firstPipeline, firstStage, company),
            200.0, 180.0, "JPY", true, "2026-02-15", "2026-01-10 00:00:00");
        updateChartDeal(newDeal(firstPipeline, firstStage, company),
            50.0, 25.0, "JPY", false, "2026-03-10", "2026-05-20 00:00:00");
        updateChartDeal(newDeal(secondPipeline, secondStage, company),
            300.0, 250.0, "USD", true, "2026-02-20", "2026-02-05 00:00:00");
        updateChartDeal(newDeal(secondPipeline, secondStage, company),
            400.0, 0.0, "USD", null, "2026-03-15", null);

        assertEquals(Map.of("2026-2", 430.0, "2026-5", 25.0),
            monthTotals(dealMapper.revenueClosedByMonth(workspace.getId(), null, null)));
        assertEquals(Map.of("2026-2", 600.0, "2026-3", 450.0),
            monthTotals(dealMapper.revenueProjectedByMonth(workspace.getId(), null)));

        List<DealStageDistributionDto> distribution =
            dealMapper.stageDistribution(workspace.getId(), null);
        DealStageDistributionDto first = distributionFor(
            distribution, firstStage.getId(), firstPipeline.getId());
        assertEquals(1, first.openCount());
        assertEquals(100.0, first.openValue(), 0.0001);
        assertEquals(2, first.closedCount());
        assertEquals(230.0, first.closedValue(), 0.0001);
        DealStageDistributionDto second = distributionFor(
            distribution, secondStage.getId(), secondPipeline.getId());
        assertEquals(1, second.openCount());
        assertEquals(400.0, second.openValue(), 0.0001);
        assertEquals(1, second.closedCount());
        assertEquals(250.0, second.closedValue(), 0.0001);

        assertEquals(Map.of("2026-2", 180.0, "2026-5", 25.0),
            monthTotals(dealMapper.revenueClosedByMonth(workspace.getId(), "JPY", null)));
        assertEquals(Map.of("2026-2", 300.0, "2026-3", 50.0),
            monthTotals(dealMapper.revenueProjectedByMonth(workspace.getId(), "JPY")));
        List<DealStageDistributionDto> filtered =
            dealMapper.stageDistribution(workspace.getId(), "JPY");
        assertEquals(1, filtered.size());
        assertEquals(firstStage.getId(), filtered.get(0).stageId());
        assertEquals(firstPipeline.getId(), filtered.get(0).pipelineId());
        assertEquals(230.0, filtered.get(0).closedValue(), 0.0001);
    }

    @Test
    void revenueClosedByMonthShiftsClosedAtByTimezoneOffset() {
        workspace = newWorkspace();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        updateChartDeal(newDeal(pipeline, stage, company),
            100.0, 90.0, "JPY", true, null, "2026-01-31 20:00:00");

        assertEquals(Map.of("2026-1", 90.0),
            monthTotals(dealMapper.revenueClosedByMonth(workspace.getId(), null, null)));
        assertEquals(Map.of("2026-2", 90.0),
            monthTotals(dealMapper.revenueClosedByMonth(workspace.getId(), null, "+09:00")));
    }

    @Test
    void dealKpiPeriodsUseRealizedCloseBoundariesAndStayWorkspaceScoped() {
        workspace = newWorkspace();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        Deal currentWon = analyticsDeal(workspace, pipeline, stage, "Current Won", 100.0, 80.0,
            "JPY", true, 20, 5, 1);
        analyticsDeal(workspace, pipeline, stage, "Current Won Two", 200.0, 120.0,
            "JPY", true, 15, 1, 1);
        analyticsDeal(workspace, pipeline, stage, "Current Lost", 60.0, 0.0,
            "JPY", false, 25, 10, 1);
        analyticsDeal(workspace, pipeline, stage, "Current Open", 40.0, 0.0,
            "JPY", null, 2, null, 1);
        analyticsDeal(workspace, pipeline, stage, "Future Close", 10.0, 999.0,
            "JPY", true, 1, -1, 1);
        analyticsDeal(workspace, pipeline, stage, "Previous Won", 70.0, 50.0,
            "JPY", true, 55, 40, 1);
        analyticsDeal(workspace, pipeline, stage, "Previous Lost", 30.0, 0.0,
            "JPY", false, 50, 45, 1);
        analyticsDeal(workspace, pipeline, stage, "Previous Open", 20.0, 0.0,
            "JPY", null, 31, null, 1);
        analyticsDeal(workspace, pipeline, stage, "Before Previous", 900.0, 800.0,
            "JPY", true, 80, 61, 1);
        analyticsDeal(workspace, pipeline, stage, "Other Currency", 700.0, 600.0,
            "USD", true, 10, 2, 1);
        jdbcTemplate.update("UPDATE deal SET expected_close_date = ? WHERE id = ?",
            "2100-01-01", currentWon.getId());

        Workspace foreignWorkspace = newWorkspace();
        Pipeline foreignPipeline = newPipelineIn(foreignWorkspace);
        Stage foreignStage = newStageIn(foreignWorkspace, foreignPipeline, 0);
        analyticsDeal(foreignWorkspace, foreignPipeline, foreignStage, "Foreign", 5000.0, 4000.0,
            "JPY", true, 10, 2, 1);
        analyticsDeal(foreignWorkspace, foreignPipeline, foreignStage, "Foreign Previous",
            4000.0, 3000.0, "JPY", true, 50, 40, 1);

        DealKpiPeriodDto current = dealMapper.dealKpiCurrent(workspace.getId(), "JPY", 30);
        DealKpiPeriodDto previous = dealMapper.dealKpiPrevious(workspace.getId(), "JPY", 30, 60);

        assertEquals(200.0, current.wonRevenue(), 0.0001);
        assertEquals(410.0, current.newPipeline(), 0.0001);
        assertEquals(5, current.newPipelineCount());
        assertEquals(2, current.wonCount());
        assertEquals(1, current.lostCount());
        assertEquals(60.0, current.lostValue(), 0.0001);
        assertEquals(14.5, current.avgCycleDays(), 0.0001);
        assertEquals(50.0, previous.wonRevenue(), 0.0001);
        assertEquals(120.0, previous.newPipeline(), 0.0001);
        assertEquals(3, previous.newPipelineCount());
        assertEquals(1, previous.wonCount());
        assertEquals(1, previous.lostCount());
        assertEquals(30.0, previous.lostValue(), 0.0001);
        assertEquals(15.0, previous.avgCycleDays(), 0.0001);
    }

    @Test
    void dealKpiPeriodsIncludeEachBoundaryInExactlyOneWindow() {
        workspace = newWorkspace();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        Deal currentBoundary = analyticsDeal(workspace, pipeline, stage, "Current Boundary",
            10.0, 10.0, "JPY", true, 1, 1, 1);
        Deal previousUpperBoundary = analyticsDeal(workspace, pipeline, stage, "Previous Upper Boundary",
            20.0, 20.0, "JPY", true, 1, 1, 1);
        Deal previousLowerBoundary = analyticsDeal(workspace, pipeline, stage, "Previous Lower Boundary",
            30.0, 0.0, "JPY", false, 1, 1, 1);
        Deal beforePreviousBoundary = analyticsDeal(workspace, pipeline, stage, "Before Previous Boundary",
            40.0, 0.0, "JPY", false, 1, 1, 1);
        setAnalyticsBoundary(currentBoundary, 30, 1);
        setAnalyticsBoundary(previousUpperBoundary, 30, -1);
        setAnalyticsBoundary(previousLowerBoundary, 60, 1);
        setAnalyticsBoundary(beforePreviousBoundary, 60, -1);

        DealKpiPeriodDto current = dealMapper.dealKpiCurrent(workspace.getId(), "JPY", 30);
        DealKpiPeriodDto previous = dealMapper.dealKpiPrevious(workspace.getId(), "JPY", 30, 60);

        assertEquals(10.0, current.wonRevenue(), 0.0001);
        assertEquals(10.0, current.newPipeline(), 0.0001);
        assertEquals(1, current.wonCount());
        assertEquals(0, current.lostCount());
        assertEquals(20.0, previous.wonRevenue(), 0.0001);
        assertEquals(50.0, previous.newPipeline(), 0.0001);
        assertEquals(1, previous.wonCount());
        assertEquals(1, previous.lostCount());
        assertEquals(30.0, previous.lostValue(), 0.0001);
    }

    @Test
    void dealKpiSeriesGroupAndClampBucketsOldestToNewest() {
        workspace = newWorkspace();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        analyticsDeal(workspace, pipeline, stage, "Newest Won", 100.0, 80.0,
            "JPY", true, 11, 1, 1);
        analyticsDeal(workspace, pipeline, stage, "Newest Lost", 50.0, 0.0,
            "JPY", false, 12, 1, 1);
        analyticsDeal(workspace, pipeline, stage, "Middle Won", 60.0, 40.0,
            "JPY", true, 25, 5, 1);
        analyticsDeal(workspace, pipeline, stage, "Oldest Won", 20.0, 20.0,
            "JPY", true, 29, 28, 1);
        analyticsDeal(workspace, pipeline, stage, "New Open", 30.0, 0.0,
            "JPY", null, 3, null, 1);

        Workspace foreignWorkspace = newWorkspace();
        Pipeline foreignPipeline = newPipelineIn(foreignWorkspace);
        Stage foreignStage = newStageIn(foreignWorkspace, foreignPipeline, 0);
        analyticsDeal(foreignWorkspace, foreignPipeline, foreignStage, "Foreign", 900.0, 800.0,
            "JPY", true, 11, 1, 1);

        Map<Integer, DealKpiClosedBucketDto> closed = dealMapper
            .dealKpiClosedSeries(workspace.getId(), "JPY", 30, 2.5).stream()
            .collect(Collectors.toMap(DealKpiClosedBucketDto::bucketIndex, value -> value));
        Map<Integer, Double> created = dealMapper
            .dealKpiNewPipelineSeries(workspace.getId(), "JPY", 30, 2.5).stream()
            .collect(Collectors.toMap(DealBucketValueDto::bucketIndex, DealBucketValueDto::value));

        assertEquals(3, closed.size());
        assertEquals(80.0, closed.get(11).wonValue(), 0.0001);
        assertEquals(1, closed.get(11).wonCount());
        assertEquals(1, closed.get(11).lostCount());
        assertEquals(10.0, closed.get(11).avgCycleDays(), 0.0001);
        assertEquals(40.0, closed.get(9).wonValue(), 0.0001);
        assertEquals(20.0, closed.get(9).avgCycleDays(), 0.0001);
        assertEquals(20.0, closed.get(0).wonValue(), 0.0001);
        assertEquals(1.0, closed.get(0).avgCycleDays(), 0.0001);
        assertEquals(150.0, created.get(7), 0.0001);
        assertEquals(60.0, created.get(1), 0.0001);
        assertEquals(20.0, created.get(0), 0.0001);
        assertEquals(30.0, created.get(10), 0.0001);
    }

    @Test
    void dealPipelineValueGroupsCurrentWonAndAllOpenDealsByPipeline() {
        workspace = newWorkspace();
        Pipeline firstPipeline = newPipeline();
        Stage firstStage = newStage(firstPipeline, 0);
        Pipeline secondPipeline = newPipeline();
        Stage secondStage = newStage(secondPipeline, 0);

        analyticsDeal(workspace, firstPipeline, firstStage, "First Won", 100.0, 90.0,
            "JPY", true, 20, 5, 1);
        analyticsDeal(workspace, firstPipeline, firstStage, "First Old Won", 100.0, 80.0,
            "JPY", true, 50, 31, 1);
        analyticsDeal(workspace, firstPipeline, firstStage, "First Future Won", 100.0, 500.0,
            "JPY", true, 1, -1, 1);
        analyticsDeal(workspace, firstPipeline, firstStage, "First Open", 100.0, 0.0,
            "JPY", null, 100, null, 1);
        analyticsDeal(workspace, firstPipeline, firstStage, "First Lost", 60.0, 0.0,
            "JPY", false, 20, 2, 1);
        analyticsDeal(workspace, secondPipeline, secondStage, "Second Won", 60.0, 50.0,
            "JPY", true, 20, 4, 1);
        analyticsDeal(workspace, secondPipeline, secondStage, "Second Open A", 20.0, 0.0,
            "JPY", null, 100, null, 1);
        analyticsDeal(workspace, secondPipeline, secondStage, "Second Open B", 30.0, 0.0,
            "JPY", null, 100, null, 1);
        analyticsDeal(workspace, firstPipeline, firstStage, "Other Currency", 1000.0, 900.0,
            "USD", true, 20, 2, 1);

        Workspace foreignWorkspace = newWorkspace();
        Pipeline foreignPipeline = newPipelineIn(foreignWorkspace);
        Stage foreignStage = newStageIn(foreignWorkspace, foreignPipeline, 0);
        analyticsDeal(foreignWorkspace, foreignPipeline, foreignStage, "Foreign Open", 5000.0, 0.0,
            "JPY", null, 1, null, 1);

        List<DealPipelineValueDto> values =
            dealMapper.dealPipelineValue(workspace.getId(), "JPY", 30);
        DealPipelineValueDto first = pipelineValueFor(values, firstPipeline.getId());
        DealPipelineValueDto second = pipelineValueFor(values, secondPipeline.getId());

        assertEquals(2, values.size());
        assertEquals(90.0, first.wonValue(), 0.0001);
        assertEquals(100.0, first.openValue(), 0.0001);
        assertEquals(1, first.openCount());
        assertEquals(50.0, second.wonValue(), 0.0001);
        assertEquals(50.0, second.openValue(), 0.0001);
        assertEquals(2, second.openCount());
    }

    @Test
    void dealAgingUsesSevenThirtyAndSixtyDayEdges() {
        workspace = newWorkspace();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        analyticsDeal(workspace, pipeline, stage, "Seven", 10.0, 0.0, "JPY", null, 7, null, 7);
        analyticsDeal(workspace, pipeline, stage, "Eight", 10.0, 0.0, "JPY", null, 8, null, 8);
        analyticsDeal(workspace, pipeline, stage, "Thirty", 10.0, 0.0, "JPY", null, 30, null, 30);
        analyticsDeal(workspace, pipeline, stage, "Thirty One", 10.0, 0.0, "JPY", null, 31, null, 31);
        analyticsDeal(workspace, pipeline, stage, "Sixty", 10.0, 0.0, "JPY", null, 60, null, 60);
        analyticsDeal(workspace, pipeline, stage, "Sixty One", 10.0, 0.0, "JPY", null, 61, null, 61);
        analyticsDeal(workspace, pipeline, stage, "Closed", 10.0, 8.0, "JPY", true, 70, 5, 61);
        analyticsDeal(workspace, pipeline, stage, "Other Currency", 10.0, 0.0,
            "USD", null, 61, null, 61);

        Workspace foreignWorkspace = newWorkspace();
        Pipeline foreignPipeline = newPipelineIn(foreignWorkspace);
        Stage foreignStage = newStageIn(foreignWorkspace, foreignPipeline, 0);
        analyticsDeal(foreignWorkspace, foreignPipeline, foreignStage, "Foreign", 10.0, 0.0,
            "JPY", null, 61, null, 61);

        List<DealAgingDto> aging = dealMapper.dealAging(workspace.getId(), "JPY");

        assertEquals(1, aging.size());
        assertEquals(stage.getId(), aging.get(0).stageId());
        assertEquals(1, aging.get(0).fresh());
        assertEquals(2, aging.get(0).active());
        assertEquals(2, aging.get(0).aging());
        assertEquals(1, aging.get(0).stalled());
    }

    @Test
    void closingSoonCountUsesInclusiveWindowOpenStateAndWorkspaceScope() {
        workspace = newWorkspace();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal today = analyticsDeal(workspace, pipeline, stage, "Today", 10.0, 0.0,
            "JPY", null, 1, null, 1);
        Deal lastDay = analyticsDeal(workspace, pipeline, stage, "Last Day", 10.0, 0.0,
            "JPY", null, 1, null, 1);
        Deal yesterday = analyticsDeal(workspace, pipeline, stage, "Yesterday", 10.0, 0.0,
            "JPY", null, 1, null, 1);
        Deal afterWindow = analyticsDeal(workspace, pipeline, stage, "After Window", 10.0, 0.0,
            "JPY", null, 1, null, 1);
        Deal closed = analyticsDeal(workspace, pipeline, stage, "Closed", 10.0, 10.0,
            "JPY", true, 10, 1, 1);
        jdbcTemplate.update("UPDATE deal SET expected_close_date = CURDATE() WHERE id = ?", today.getId());
        jdbcTemplate.update("UPDATE deal SET expected_close_date = DATE_ADD(CURDATE(), INTERVAL 7 DAY) WHERE id = ?",
            lastDay.getId());
        jdbcTemplate.update("UPDATE deal SET expected_close_date = DATE_SUB(CURDATE(), INTERVAL 1 DAY) WHERE id = ?",
            yesterday.getId());
        jdbcTemplate.update("UPDATE deal SET expected_close_date = DATE_ADD(CURDATE(), INTERVAL 8 DAY) WHERE id = ?",
            afterWindow.getId());
        jdbcTemplate.update("UPDATE deal SET expected_close_date = CURDATE() WHERE id = ?", closed.getId());

        Workspace foreignWorkspace = newWorkspace();
        Pipeline foreignPipeline = newPipelineIn(foreignWorkspace);
        Stage foreignStage = newStageIn(foreignWorkspace, foreignPipeline, 0);
        Deal foreign = analyticsDeal(foreignWorkspace, foreignPipeline, foreignStage, "Foreign", 10.0, 0.0,
            "JPY", null, 1, null, 1);
        jdbcTemplate.update("UPDATE deal SET expected_close_date = CURDATE() WHERE id = ?", foreign.getId());

        assertEquals(2, dealMapper.closingSoonCount(workspace.getId(), 7));
        assertEquals(1, dealMapper.closingSoonCount(foreignWorkspace.getId(), 7));
    }

    @Test
    void topDealsOrderLimitFilterAndStayWorkspaceScoped() {
        workspace = newWorkspace();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        Deal open500 = analyticsDeal(workspace, pipeline, stage, "Open 500", 500.0, 0.0,
            "JPY", null, 5, null, 1);
        Deal open400 = analyticsDeal(workspace, pipeline, stage, "Open 400", 400.0, 0.0,
            "JPY", null, 5, null, 1);
        Deal open300 = analyticsDeal(workspace, pipeline, stage, "Open 300", 300.0, 0.0,
            "JPY", null, 5, null, 1);
        analyticsDeal(workspace, pipeline, stage, "Open 200", 200.0, 0.0,
            "JPY", null, 5, null, 1);
        analyticsDeal(workspace, pipeline, stage, "Open 100", 100.0, 0.0,
            "JPY", null, 5, null, 1);
        Deal won50 = analyticsDeal(workspace, pipeline, stage, "Won 50", 100.0, 50.0,
            "JPY", true, 20, 5, 1);
        Deal won40 = analyticsDeal(workspace, pipeline, stage, "Won 40", 500.0, 40.0,
            "JPY", true, 20, 5, 1);
        Deal won30 = analyticsDeal(workspace, pipeline, stage, "Won 30", 400.0, 30.0,
            "JPY", true, 20, 5, 1);
        analyticsDeal(workspace, pipeline, stage, "Won 20", 300.0, 20.0,
            "JPY", true, 20, 5, 1);
        analyticsDeal(workspace, pipeline, stage, "Lost High", 5000.0, 4000.0,
            "JPY", false, 20, 5, 1);
        analyticsDeal(workspace, pipeline, stage, "USD High", 6000.0, 5000.0,
            "USD", true, 20, 5, 1);

        Workspace foreignWorkspace = newWorkspace();
        Pipeline foreignPipeline = newPipelineIn(foreignWorkspace);
        Stage foreignStage = newStageIn(foreignWorkspace, foreignPipeline, 0);
        analyticsDeal(foreignWorkspace, foreignPipeline, foreignStage, "Foreign High", 7000.0, 6000.0,
            "JPY", true, 20, 5, 1);
        analyticsDeal(foreignWorkspace, foreignPipeline, foreignStage, "Foreign Open", 7000.0, 0.0,
            "JPY", null, 5, null, 1);

        assertEquals(List.of(open500.getId(), open400.getId(), open300.getId()),
            dealMapper.topOpenDeals(workspace.getId(), "JPY").stream().map(Deal::getId).toList());
        assertEquals(List.of(won50.getId(), won40.getId(), won30.getId()),
            dealMapper.topWonDeals(workspace.getId(), "JPY").stream().map(Deal::getId).toList());
    }

    @Test
    void dealFacetsCountWorkspaceDeals() {
        workspace = newWorkspace();
        Pipeline pipeline = newPipeline();
        Stage firstStage = newStage(pipeline, 0);
        Stage secondStage = newStage(pipeline, 1);
        Company company = newCompany();

        updateDeal(newDeal(pipeline, firstStage, company), "Open", 100.0, 0.0, "JPY", null);
        updateDeal(newDeal(pipeline, firstStage, company), "Won", 200.0, 180.0, "JPY", true);
        updateDeal(newDeal(pipeline, firstStage, company), "Lost", 50.0, 0.0, "JPY", false);
        Deal withoutCompany = updateDeal(
            newDeal(pipeline, secondStage, company), "USD Won", 300.0, 250.0, "USD", true);
        withoutCompany.setCompanyId(null);
        dealMapper.update(withoutCompany);

        assertEquals(Map.of("open", 1L, "won", 2L, "lost", 1L),
            facetCounts(dealMapper.countsByStatus(workspace.getId())));
        assertEquals(Map.of(
            Integer.toString(firstStage.getId()), 3L,
            Integer.toString(secondStage.getId()), 1L
        ), facetCounts(dealMapper.countsByStage(workspace.getId())));
        assertEquals(Map.of(Integer.toString(pipeline.getId()), 4L),
            facetCounts(dealMapper.countsByPipeline(workspace.getId())));
        assertEquals(Map.of(Integer.toString(company.getId()), 3L),
            facetCounts(dealMapper.countsByCompany(workspace.getId())));
        assertEquals(Map.of("JPY", 3L, "USD", 1L),
            facetCounts(dealMapper.countsByCurrency(workspace.getId())));
    }

    @Test
    void getDealsPageAndCountApplySameFiltersAndSort() {
        workspace = newWorkspace();
        Pipeline pipeline = newPipeline();
        Pipeline otherPipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Stage otherStage = newStage(pipeline, 1);
        Stage foreignPipelineStage = newStage(otherPipeline, 0);
        Company company = newCompany();
        Company otherCompany = newCompany();

        Deal won = updateDeal(newDeal(pipeline, stage, company), "Target Won", 200.0, 180.0, "JPY", true);
        Deal lost = updateDeal(newDeal(pipeline, stage, company), "Target Lost", 50.0, 0.0, "JPY", false);
        updateDeal(newDeal(pipeline, stage, company), "Target Open", 500.0, 0.0, "JPY", null);
        updateDeal(newDeal(pipeline, stage, company), "Target USD", 400.0, 350.0, "USD", true);
        updateDeal(newDeal(pipeline, otherStage, company), "Target Other Stage", 300.0, 250.0, "JPY", true);
        updateDeal(newDeal(pipeline, stage, otherCompany), "Target Other Company", 275.0, 225.0, "JPY", true);
        updateDeal(newDeal(otherPipeline, foreignPipelineStage, company),
            "Target Other Pipeline", 250.0, 200.0, "JPY", true);
        updateDeal(newDeal(pipeline, stage, company), "Different Name", 225.0, 190.0, "JPY", true);

        List<Deal> page = dealMapper.getDealsPage(
            workspace.getId(), "%Target%", "value", "desc", "JPY", pipeline.getId(),
            stage.getId(), company.getId(), "closed", 10, 0);
        long count = dealMapper.countDeals(
            workspace.getId(), "%Target%", "JPY", pipeline.getId(), stage.getId(), company.getId(), "closed");

        assertEquals(2, count);
        assertEquals(List.of(won.getId(), lost.getId()), page.stream().map(Deal::getId).toList());
    }

    /**
     * Updates a deal and checks if the new values are persisted.
     */
    @Test
    void update_persistsNewValues() {
        Pipeline pipeline = newPipeline();
        Stage stage1 = newStage(pipeline, 0);
        Stage stage2 = newStage(pipeline, 1);
        Deal deal = newDeal(pipeline, stage1, newCompany());

        deal.setName("Renamed Deal");
        deal.setValue(2500.50);
        deal.setCurrency("JPY");
        deal.setStageId(stage2.getId());
        deal.setCompanyId(null);

        dealMapper.update(deal);

        Deal found = dealMapper.getDealById(workspace.getId(), deal.getId());
        assertEquals("Renamed Deal", found.getName());
        assertEquals(2500.50, found.getValue());
        assertEquals("JPY", found.getCurrency());
        assertEquals(stage2.getId(), found.getStageId());
        assertNull(found.getCompanyId());
    }

    /**
     * Deletes a deal and checks if the deal is removed.
     */
    @Test
    void delete_removesRow() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());

        dealMapper.delete(workspace.getId(), deal.getId());

        assertNull(dealMapper.getDealById(workspace.getId(), deal.getId()));
    }

    /**
     * Gets deals by pipeline ID and checks if the returned list includes the inserted deal.
     */
    @Test
    void getDealsByPipelineId_filtersByPipeline() {
        Pipeline pipelineA = newPipeline();
        Pipeline pipelineB = newPipeline();
        Stage stageA = newStage(pipelineA, 0);
        Stage stageB = newStage(pipelineB, 0);
        Deal dealA = newDeal(pipelineA, stageA, newCompany());
        Deal dealB = newDeal(pipelineB, stageB, newCompany());

        List<Deal> matched = dealMapper.getDealsByPipelineId(workspace.getId(), pipelineA.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == dealA.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == dealB.getId()));
    }

    /**
     * Gets deals by stage ID and checks if the returned list includes the inserted deal.
     */
    @Test
    void getDealsByStageId_filtersByStage() {
        Pipeline pipeline = newPipeline();
        Stage stage1 = newStage(pipeline, 0);
        Stage stage2 = newStage(pipeline, 1);
        Deal deal1 = newDeal(pipeline, stage1, newCompany());
        Deal deal2 = newDeal(pipeline, stage2, newCompany());

        List<Deal> matched = dealMapper.getDealsByStageId(workspace.getId(), stage1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == deal1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == deal2.getId()));
    }

    /**
     * Gets deals by company ID and checks if the returned list includes the inserted deal.
     */
    @Test
    void getDealsByCompanyId_filtersByCompany() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company1 = newCompany();
        Company company2 = newCompany();
        Deal deal1 = newDeal(pipeline, stage, company1);
        Deal deal2 = newDeal(pipeline, stage, company2);

        List<Deal> matched = dealMapper.getDealsByCompanyId(workspace.getId(), company1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == deal1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == deal2.getId()));
    }

    /**
     * Adds a person to a deal and checks if the returned list includes the inserted deal.
     */
    @Test
    void addPerson_thenGetDealsByPersonId_returnsDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        Person person = newPerson(company);

        dealMapper.addPerson(workspace.getId(), deal.getId(), person.getId(), null);

        List<Deal> matched = dealMapper.getDealsByPersonId(workspace.getId(), person.getId());
        assertTrue(matched.stream().anyMatch(x -> x.getId() == deal.getId()));
    }

    /**
     * Adds a person to a deal and checks if the person is added only once.
     */
    @Test
    void addPerson_isIdempotent() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        Person person = newPerson(newCompany());

        dealMapper.addPerson(workspace.getId(), deal.getId(), person.getId(), null);
        dealMapper.addPerson(workspace.getId(), deal.getId(), person.getId(), null);

        long matching = dealMapper.getDealsByPersonId(workspace.getId(), person.getId()).stream()
                .filter(x -> x.getId() == deal.getId()).count();
        assertEquals(1, matching);
    }

    /**
     * Removes a person from a deal and checks if the person is removed.
     */
    @Test
    void removePerson_dropsAssociation() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        Person person = newPerson(newCompany());
        dealMapper.addPerson(workspace.getId(), deal.getId(), person.getId(), null);

        dealMapper.removePerson(workspace.getId(), deal.getId(), person.getId());

        assertTrue(dealMapper.getDealsByPersonId(workspace.getId(), person.getId()).stream()
                .noneMatch(x -> x.getId() == deal.getId()));
    }

    /**
     * Adds a tag to a deal and checks if the returned list includes the inserted deal.
     */
    @Test
    void addTag_thenGetDealsByTagId_returnsDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        Tag tag = newTag();

        dealMapper.addTag(workspace.getId(), deal.getId(), tag.getId());

        List<Deal> matched = dealMapper.getDealsByTagId(workspace.getId(), tag.getId());
        assertTrue(matched.stream().anyMatch(x -> x.getId() == deal.getId()));
    }

    /**
     * Removes a tag from a deal and checks if the tag is removed.
     */
    @Test
    void removeTag_dropsAssociation() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        Tag tag = newTag();
        dealMapper.addTag(workspace.getId(), deal.getId(), tag.getId());

        dealMapper.removeTag(workspace.getId(), deal.getId(), tag.getId());

        assertTrue(dealMapper.getDealsByTagId(workspace.getId(), tag.getId()).stream()
                .noneMatch(x -> x.getId() == deal.getId()));
    }

    @Test
    void workspaceScopeHidesDealsAndBlocksMutations() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        Workspace other = new Workspace();
        other.setName("Other Workspace");
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);

        assertNull(dealMapper.getDealById(other.getId(), deal.getId()));
        assertEquals(0, dealMapper.delete(other.getId(), deal.getId()));
        assertNotNull(dealMapper.getDealById(workspace.getId(), deal.getId()));
    }

    /**
     * The risk-evaluation opt-out toggle round-trips and is workspace-scoped.
     */
    @Test
    void updateRiskExcluded_togglesFlagAndIsWorkspaceScoped() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        assertFalse(dealMapper.getDealById(workspace.getId(), deal.getId()).isRiskExcluded());

        assertEquals(1, dealMapper.updateRiskExcluded(workspace.getId(), deal.getId(), true));
        assertTrue(dealMapper.getDealById(workspace.getId(), deal.getId()).isRiskExcluded());

        Workspace other = new Workspace();
        other.setName("Other Workspace");
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);
        assertEquals(0, dealMapper.updateRiskExcluded(other.getId(), deal.getId(), false));
        assertTrue(dealMapper.getDealById(workspace.getId(), deal.getId()).isRiskExcluded());
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }

    private Pipeline newPipelineIn(Workspace ws) {
        return newPipelineIn(ws, "Pipeline " + unique());
    }

    private Pipeline newPipelineIn(Workspace ws, String name) {
        Pipeline pipeline = new Pipeline();
        pipeline.setName(name);
        pipeline.setWorkspaceId(ws.getId());
        pipelineMapper.insertPipeline(pipeline);
        return pipeline;
    }

    private Stage newStageIn(Workspace ws, Pipeline pipeline, int position) {
        return newStageIn(ws, pipeline, "Stage " + unique(), position);
    }

    private Stage newStageIn(Workspace ws, Pipeline pipeline, String name, int position) {
        Stage stage = new Stage();
        stage.setName(name);
        stage.setPipeline(pipeline);
        stage.setPosition(position);
        stage.setWorkspaceId(ws.getId());
        pipelineMapper.insertStage(stage);
        return stage;
    }

    private Company newCompanyIn(Workspace ws, String name) {
        Company company = new Company();
        company.setName(name);
        company.setWorkspaceId(ws.getId());
        companyMapper.insert(company);
        return company;
    }

    private Deal newDealIn(Workspace ws, Pipeline pipeline, Stage stage) {
        Deal deal = new Deal();
        deal.setName("Deal " + unique());
        deal.setWorkspaceId(ws.getId());
        deal.setValue(1000.0);
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        dealMapper.insert(deal);
        return deal;
    }

    private Deal analyticsDeal(Workspace ws, Pipeline pipeline, Stage stage, String name,
            double value, double actualValue, String currency, Boolean won,
            int createdDaysAgo, Integer closedDaysAgo, int updatedDaysAgo) {
        Deal deal = newDealIn(ws, pipeline, stage);
        deal.setName(name);
        deal.setValue(value);
        deal.setActualValue(actualValue);
        deal.setCurrency(currency);
        deal.setWon(won);
        deal.setClosedAt(won == null ? null : "2000-01-01 00:00:00");
        dealMapper.update(deal);
        if (won == null) {
            jdbcTemplate.update("""
                UPDATE deal
                SET created_at = DATE_SUB(NOW(), INTERVAL ? DAY),
                    updated_at = DATE_SUB(NOW(), INTERVAL ? DAY)
                WHERE workspace_id = ? AND id = ?
                """, createdDaysAgo, updatedDaysAgo, ws.getId(), deal.getId());
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
                """, createdDaysAgo, closedDaysAgo, updatedDaysAgo, ws.getId(), deal.getId());
        }
        return deal;
    }

    private void setAnalyticsBoundary(Deal deal, int daysAgo, int minuteOffset) {
        jdbcTemplate.update("""
            UPDATE deal
            SET created_at = DATE_ADD(DATE_SUB(NOW(), INTERVAL ? DAY), INTERVAL ? MINUTE),
                closed_at = DATE_ADD(DATE_SUB(NOW(), INTERVAL ? DAY), INTERVAL ? MINUTE)
            WHERE workspace_id = ? AND id = ?
            """, daysAgo, minuteOffset, daysAgo, minuteOffset, workspace.getId(), deal.getId());
    }

    private List<Integer> dealPageIds(Workspace ws, String sort, String dir) {
        return dealMapper.getDealsPage(
            ws.getId(), null, sort, dir, null, null, null, null, null, 100, 0)
            .stream().map(Deal::getId).toList();
    }

    private Deal updateDeal(Deal deal, String name, double value, double actualValue,
            String currency, Boolean won) {
        deal.setName(name);
        deal.setValue(value);
        deal.setActualValue(actualValue);
        deal.setCurrency(currency);
        deal.setWon(won);
        deal.setClosedAt(won == null ? null : "2026-01-01 00:00:00");
        dealMapper.update(deal);
        return deal;
    }

    private Deal updateChartDeal(Deal deal, double value, double actualValue, String currency,
            Boolean won, String expectedCloseDate, String closedAt) {
        deal.setValue(value);
        deal.setActualValue(actualValue);
        deal.setCurrency(currency);
        deal.setWon(won);
        deal.setExpectedCloseDate(expectedCloseDate);
        deal.setClosedAt(closedAt);
        dealMapper.update(deal);
        return deal;
    }

    private DealCurrencyMetricsDto metricsFor(List<DealCurrencyMetricsDto> metrics, String currency) {
        return metrics.stream()
            .filter(item -> currency.equals(item.currency()))
            .findFirst()
            .orElseThrow();
    }

    private Map<String, Long> facetCounts(List<FacetCount> facets) {
        return facets.stream().collect(Collectors.toMap(FacetCount::getKey, FacetCount::getCount));
    }

    private Map<String, Double> monthTotals(List<DealMonthTotalDto> totals) {
        return totals.stream().collect(Collectors.toMap(
            total -> total.year() + "-" + total.month(), DealMonthTotalDto::total));
    }

    private DealStageDistributionDto distributionFor(List<DealStageDistributionDto> distribution,
            int stageId, int pipelineId) {
        return distribution.stream()
            .filter(item -> item.stageId() == stageId && item.pipelineId() == pipelineId)
            .findFirst()
            .orElseThrow();
    }

    private DealPipelineValueDto pipelineValueFor(List<DealPipelineValueDto> values, int pipelineId) {
        return values.stream()
            .filter(item -> item.pipelineId() == pipelineId)
            .findFirst()
            .orElseThrow();
    }
}
