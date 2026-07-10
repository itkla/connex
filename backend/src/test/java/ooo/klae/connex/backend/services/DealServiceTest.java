package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealStageHistory;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DealCurrencyMetricsDto;
import ooo.klae.connex.backend.dto.DealFacets;
import ooo.klae.connex.backend.dto.DealMetricsDto;
import ooo.klae.connex.backend.dto.DealMonthTotalDto;
import ooo.klae.connex.backend.dto.DealRevenueSeriesDto;
import ooo.klae.connex.backend.dto.DealStageDistributionDto;
import ooo.klae.connex.backend.dto.DealSummaryDto;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

class DealServiceTest extends AbstractServiceTest {

    @Autowired DealService dealService;

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

        Workspace otherWorkspace = newWorkspace();
        Pipeline otherPipeline = newPipelineIn(otherWorkspace);
        Stage otherStage = newStageIn(otherWorkspace, otherPipeline);
        Company otherCompany = newCompanyIn(otherWorkspace);
        Deal foreign = new Deal();
        foreign.setWorkspaceId(otherWorkspace.getId());
        foreign.setName("Foreign Won");
        foreign.setValue(1000.0);
        foreign.setActualValue(900.0);
        foreign.setCurrency("USD");
        foreign.setPipelineId(otherPipeline.getId());
        foreign.setStageId(otherStage.getId());
        foreign.setCompanyId(otherCompany.getId());
        foreign.setWon(true);
        foreign.setClosedAt("2026-01-01 00:00:00");
        dealMapper.insert(foreign);

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
        foreign.setValue(1000.0);
        foreign.setActualValue(900.0);
        foreign.setCurrency("USD");
        foreign.setPipelineId(otherPipeline.getId());
        foreign.setStageId(otherStage.getId());
        foreign.setExpectedCloseDate("2026-02-20");
        foreign.setClosedAt("2026-01-25 00:00:00");
        foreign.setWon(true);
        dealMapper.insert(foreign);

        DealRevenueSeriesDto series = dealService.getRevenueTimeseries(null);
        List<DealStageDistributionDto> distribution = dealService.getStageDistribution(null);
        DealRevenueSeriesDto filteredSeries = dealService.getRevenueTimeseries("JPY");
        List<DealStageDistributionDto> filteredDistribution = dealService.getStageDistribution("JPY");

        assertEquals(Map.of("2026-1", 180.0, "2026-3", 25.0, "2026-4", 550.0),
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
        assertEquals(Map.of("2026-1", 180.0, "2026-3", 25.0),
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
        assertEquals(pipeline.getName(), summary.getPipelineName());
        assertEquals(stage.getName(), summary.getStageName());
        assertEquals(company.getName(), summary.getCompanyName());
        assertEquals(currentUser.getDisplayName(), summary.getOwnerName());
        assertEquals("open", summary.getStatus());
    }

    @Test
    void getDealSummary_throwsWhenDealMissing() {
        assertThrows(ResourceNotFoundException.class, () -> dealService.getDealSummary(-1));
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
        Deal d3 = newDeal(pipeline, to, company);

        dealService.move(d1.getId(), to.getId(), 0);

        List<Deal> target = dealService.getDealsByStageId(to.getId());
        assertEquals(List.of(d1.getId(), d3.getId()), target.stream().map(Deal::getId).toList());
        assertEquals(List.of(0, 1), target.stream().map(Deal::getPosition).toList());

        List<Deal> source = dealService.getDealsByStageId(from.getId());
        assertEquals(List.of(d2.getId()), source.stream().map(Deal::getId).toList());
        assertEquals(0, source.get(0).getPosition());
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
    void create_recordsInitialStageHistory() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();

        Deal deal = new Deal();
        deal.setName("Deal " + unique());
        deal.setWorkspaceId(workspace.getId());
        deal.setValue(1000.0);
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        Deal created = dealService.create(deal);

        List<DealStageHistory> history = dealService.getStageHistory(created.getId());
        assertEquals(1, history.size());
        assertEquals(stage.getId(), history.get(0).getStageId());
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
    void reopen_fromTerminalStage_recordsReturnStageHistory() {
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
        assertEquals(open.getId(), history.get(1).getStageId());
        assertEquals(open.getId(), dealService.getDealById(deal.getId()).getStageId());
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
        assertEquals(1000.0, after.getValue(), 0.0001);
        assertEquals(stage.getId(), after.getStageId());
        assertEquals(pipeline.getId(), after.getPipelineId());
    }

    @Test
    void reschedule_doesNotReopenClosedDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        dealService.close(deal.getId(), Boolean.TRUE, "signed", 1500.0);

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

    private Map<String, Long> facetCounts(List<FacetCount> facets) {
        return facets.stream().collect(Collectors.toMap(FacetCount::getKey, FacetCount::getCount));
    }

    private Map<String, Double> monthTotals(List<DealMonthTotalDto> totals) {
        return totals.stream().collect(Collectors.toMap(
            total -> total.year() + "-" + total.month(), DealMonthTotalDto::total));
    }
}
