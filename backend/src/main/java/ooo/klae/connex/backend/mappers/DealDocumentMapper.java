package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.DealDocument;

/** Mapper for {@code deal_document}; every statement is workspace-scoped. */
public interface DealDocumentMapper {
    List<DealDocument> getByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    DealDocument getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Integer maxVersion(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    int insert(DealDocument document);
    int updateStatus(@Param("workspaceId") int workspaceId, @Param("id") int id, @Param("status") String status);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
