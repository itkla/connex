package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.dto.BoardPositionUpdate;

class DealEditVersionMapperTest extends AbstractMapperTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;

    @Test
    void everyTargetedAndLegacyWriterAdvancesTheDatabaseCounter() {
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        int workspaceId = workspace.getId();
        int id = deal.getId();
        List<Runnable> writes = List.of(
            () -> dealMapper.updateName(workspaceId, id, "Renamed", "renamed"),
            () -> dealMapper.updateValueAndSource(workspaceId, id, BigDecimal.TEN, "manual"),
            () -> dealMapper.updateValueSource(workspaceId, id, "manual"),
            () -> dealMapper.updateActualValue(workspaceId, id, BigDecimal.ONE),
            () -> dealMapper.updateExpectedCloseDate(workspaceId, id, "2026-12-01"),
            () -> dealMapper.updateRiskExcluded(workspaceId, id, true),
            () -> dealMapper.updateOwner(workspaceId, id, null),
            () -> dealMapper.setPositions(workspaceId, stage.getId(), List.of(new BoardPositionUpdate(id, 2))),
            () -> dealMapper.updateOutcome(deal),
            () -> dealMapper.update(deal),
            () -> {
                jdbcTemplate.update("UPDATE deal SET name = ?, updated_at = updated_at WHERE workspace_id = ? AND id = ?",
                    "Timestamp-preserving maintenance", workspaceId, id);
                sqlSessionTemplate.clearCache();
            }
        );
        for (Runnable write : writes) {
            long before = dealMapper.getDealById(workspaceId, id).getVersion();
            write.run();
            assertTrue(dealMapper.getDealById(workspaceId, id).getVersion() > before);
        }
    }

    @Test
    void companyDeletionAdvancesVersionsWhenTheForeignKeyDetachesSurvivingDeals() {
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 0);
        var company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        long before = dealMapper.getDealById(workspace.getId(), deal.getId()).getVersion();
        jdbcTemplate.update("DELETE FROM company WHERE workspace_id = ? AND id = ?", workspace.getId(), company.getId());
        sqlSessionTemplate.clearCache();
        Deal detached = dealMapper.getDealById(workspace.getId(), deal.getId());
        org.junit.jupiter.api.Assertions.assertNull(detached.getCompanyId());
        assertTrue(detached.getVersion() > before);
    }

    @Test
    void staleAndForeignWorkspaceUpdatesWriteNothingAndLegacyUpdateStillWorks() {
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());
        deal.setExpectedVersion(0L);
        dealMapper.updateValueAndSource(workspace.getId(), deal.getId(), new BigDecimal("99999"), "manual");
        assertEquals(0, dealMapper.update(deal));
        deal.setExpectedVersion(dealMapper.getDealById(workspace.getId(), deal.getId()).getVersion());
        deal.setWorkspaceId(-1);
        assertEquals(0, dealMapper.update(deal));
        deal.setWorkspaceId(workspace.getId());
        deal.setExpectedVersion(null);
        assertEquals(1, dealMapper.update(deal));
        assertEquals(0, new BigDecimal("99999").compareTo(dealMapper.getDealById(workspace.getId(), deal.getId()).getValue()));
    }
}
