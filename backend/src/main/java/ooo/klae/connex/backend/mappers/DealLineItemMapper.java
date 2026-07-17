package ooo.klae.connex.backend.mappers;

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
    DealLineItem getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int insert(DealLineItem item);
    int update(DealLineItem item);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
