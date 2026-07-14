package ooo.klae.connex.backend.mappers;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealStageHistory;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;

class DealStageHistoryMapperTest extends AbstractMapperTest {

    @Autowired DealStageHistoryMapper historyMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    private DealStageHistory record(int dealId, int stageId, String achievedAt) {
        return record(dealId, stageId, achievedAt, true);
    }

    private DealStageHistory record(int dealId, int stageId, String achievedAt, boolean conversionEligible) {
        DealStageHistory history = new DealStageHistory();
        history.setWorkspaceId(workspace.getId());
        history.setDealId(dealId);
        history.setStageId(stageId);
        history.setAchievedAt(achievedAt);
        history.setConversionEligible(conversionEligible);
        historyMapper.insert(history);
        return history;
    }

    @Test
    void insert_assignsGeneratedId() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);

        DealStageHistory history = record(deal.getId(), stage.getId(), "2024-06-01 10:00:00");

        assertNotEquals(0, history.getId());
    }

    @Test
    void databaseDefaultKeepsLegacyWriterHistoryIneligible() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        jdbcTemplate.update(
            "INSERT INTO deal_stage_history (workspace_id, deal_id, stage_id, achieved_at) VALUES (?, ?, ?, ?)",
            workspace.getId(), deal.getId(), stage.getId(), "2024-06-01 10:00:00");

        List<DealStageHistory> history = historyMapper.getByDealId(workspace.getId(), deal.getId());

        assertEquals(1, history.size());
        assertFalse(history.getFirst().isConversionEligible());
    }

    @Test
    void openDealSeedPromotesOnlyLatestCurrentStageHistory() throws Exception {
        Pipeline pipeline = newPipeline();
        Stage prior = newStage(pipeline, 0);
        Stage current = newStage(pipeline, 1);
        Deal openDeal = newDeal(pipeline, current, newCompany());
        record(openDeal.getId(), prior.getId(), "2024-06-01 10:00:00", false);
        record(openDeal.getId(), current.getId(), "2024-06-02 10:00:00", false);
        record(openDeal.getId(), current.getId(), "2024-06-03 10:00:00", false);
        Deal closedDeal = newDeal(pipeline, current, newCompany());
        closedDeal.setWon(true);
        closedDeal.setClosedAt("2024-06-04 10:00:00");
        dealMapper.update(closedDeal);
        record(closedDeal.getId(), current.getId(), "2024-06-04 09:00:00", false);

        String resource = "db/migration/tenant/V72__seed_open_deal_conversion_history.sql";
        String sql;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        jdbcTemplate.execute(sql);
        jdbcTemplate.execute(sql);

        List<DealStageHistory> openHistory = historyMapper.getByDealId(workspace.getId(), openDeal.getId());
        List<DealStageHistory> closedHistory = historyMapper.getByDealId(workspace.getId(), closedDeal.getId());
        assertEquals(3, openHistory.size());
        assertFalse(openHistory.get(0).isConversionEligible());
        assertFalse(openHistory.get(1).isConversionEligible());
        assertTrue(openHistory.get(2).isConversionEligible());
        assertEquals(1, closedHistory.size());
        assertFalse(closedHistory.getFirst().isConversionEligible());
    }

    @Test
    void getByDealId_returnsRowsEarliestFirst() {
        Pipeline pipeline = newPipeline();
        Stage first = newStage(pipeline, 0);
        Stage second = newStage(pipeline, 1);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, first, company);

        record(deal.getId(), second.getId(), "2024-06-03 09:00:00");
        record(deal.getId(), first.getId(), "2024-06-01 09:00:00");

        List<DealStageHistory> history = historyMapper.getByDealId(workspace.getId(), deal.getId());

        assertEquals(2, history.size());
        assertEquals(first.getId(), history.get(0).getStageId());
        assertEquals(second.getId(), history.get(1).getStageId());
        assertTrue(history.stream().allMatch(DealStageHistory::isConversionEligible));
    }

    @Test
    void getByDealId_scopesToWorkspaceAndDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        Deal other = newDeal(pipeline, stage, company);
        record(deal.getId(), stage.getId(), "2024-06-01 10:00:00");
        record(other.getId(), stage.getId(), "2024-06-01 10:00:00");

        List<DealStageHistory> forDeal = historyMapper.getByDealId(workspace.getId(), deal.getId());
        assertEquals(1, forDeal.size());
        assertTrue(forDeal.stream().allMatch(h -> h.getDealId() == deal.getId()));

        List<DealStageHistory> foreignWorkspace = historyMapper.getByDealId(workspace.getId() + 1000, deal.getId());
        assertTrue(foreignWorkspace.isEmpty());
    }
}
