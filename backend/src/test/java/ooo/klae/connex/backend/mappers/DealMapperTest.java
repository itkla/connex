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

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.DealCurrencyMetricsDto;
import ooo.klae.connex.backend.dto.DealMonthTotalDto;
import ooo.klae.connex.backend.dto.DealStageDistributionDto;
import ooo.klae.connex.backend.dto.FacetCount;

class DealMapperTest extends AbstractMapperTest {

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
            50.0, 25.0, "JPY", false, "2026-03-10", "2026-03-20 00:00:00");
        updateChartDeal(newDeal(secondPipeline, secondStage, company),
            300.0, 250.0, "USD", true, "2026-02-20", "2026-02-05 00:00:00");
        updateChartDeal(newDeal(secondPipeline, secondStage, company),
            400.0, 0.0, "USD", null, "2026-03-15", null);

        assertEquals(Map.of("2026-1", 180.0, "2026-2", 250.0, "2026-3", 25.0),
            monthTotals(dealMapper.revenueClosedByMonth(workspace.getId(), null)));
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

        assertEquals(Map.of("2026-1", 180.0, "2026-3", 25.0),
            monthTotals(dealMapper.revenueClosedByMonth(workspace.getId(), "JPY")));
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
        Pipeline pipeline = new Pipeline();
        pipeline.setName("Pipeline " + unique());
        pipeline.setWorkspaceId(ws.getId());
        pipelineMapper.insertPipeline(pipeline);
        return pipeline;
    }

    private Stage newStageIn(Workspace ws, Pipeline pipeline, int position) {
        Stage stage = new Stage();
        stage.setName("Stage " + unique());
        stage.setPipeline(pipeline);
        stage.setPosition(position);
        stage.setWorkspaceId(ws.getId());
        pipelineMapper.insertStage(stage);
        return stage;
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
}
