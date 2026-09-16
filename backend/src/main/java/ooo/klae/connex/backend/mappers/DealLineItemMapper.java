package ooo.klae.connex.backend.mappers;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.DealLineItem;

/**
 * Mapper for {@code deal_line_item} persistence. SQL lives in
 * {@code resources/mappers/DealLineItemMapper.xml}. Every statement is workspace-scoped.
 */
public interface DealLineItemMapper {
    List<DealLineItem> getByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    int countByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    int countByDealIdForUpdate(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    /** Detects line totals that cannot be interpreted in the parent deal currency. */
    boolean hasCurrencyMismatch(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId,
        @Param("currency") String currency);
    /** Counts foreign-currency lines so a locked mutation can prove repair progress. */
    int countCurrencyMismatches(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId,
        @Param("currency") String currency);
    BigDecimal sumLineTotals(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    DealLineItem getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int insert(DealLineItem item);
    int update(DealLineItem item);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
