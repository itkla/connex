package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.DealStageHistory;

import java.util.List;

/**
 * Mapper interface for {@code DealStageHistory} (per-deal stage-achievement log).
 * SQL is defined in {@code resources/mappers/DealStageHistoryMapper.xml}.
 */
public interface DealStageHistoryMapper {
    int insert(DealStageHistory history);
    List<DealStageHistory> getByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
}
