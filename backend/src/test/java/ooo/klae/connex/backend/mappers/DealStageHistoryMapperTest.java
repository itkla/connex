package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealStageHistory;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;

class DealStageHistoryMapperTest extends AbstractMapperTest {

    @Autowired DealStageHistoryMapper historyMapper;

    private DealStageHistory record(int dealId, int stageId, String achievedAt) {
        DealStageHistory history = new DealStageHistory();
        history.setWorkspaceId(workspace.getId());
        history.setDealId(dealId);
        history.setStageId(stageId);
        history.setAchievedAt(achievedAt);
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
