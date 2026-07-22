package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.DocumentApproval;

/** Mapper for {@code document_approval}; every statement is workspace-scoped. */
public interface DocumentApprovalMapper {
    List<DocumentApproval> getByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    List<DocumentApproval> getByDocumentId(@Param("workspaceId") int workspaceId, @Param("documentId") int documentId);
    DocumentApproval getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    DocumentApproval findPending(@Param("workspaceId") int workspaceId, @Param("documentId") int documentId);
    int insert(DocumentApproval approval);
    int decide(@Param("workspaceId") int workspaceId, @Param("id") int id, @Param("status") String status,
        @Param("decidedBy") Integer decidedBy, @Param("decisionComment") String decisionComment);
}
