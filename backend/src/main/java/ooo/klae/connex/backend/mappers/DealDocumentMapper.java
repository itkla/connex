package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.dto.GeneratedDocumentSummaryDto;
import ooo.klae.connex.backend.dto.MemberScope;

/** Mapper for {@code deal_document}; every statement is workspace-scoped. */
public interface DealDocumentMapper {
    List<DealDocument> getByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);

    /**
     * One bounded page of generated documents across every deal in the workspace.
     *
     * @param workspaceId the resolved tenant
     * @param query the escaped {@code LIKE} pattern over document title and deal name, or null
     * @param statuses the requested document statuses, or null for every status
     * @param types the requested document types, or null for every type
     * @param dealId one parent deal to restrict to, or null for every deal
     * @param memberScope the parent deal's ownership scope
     * @param limit the page size
     * @param offset the page offset
     * @return the page rows, newest first
     */
    List<GeneratedDocumentSummaryDto> getWorkspacePage(
        @Param("workspaceId") int workspaceId,
        @Param("query") String query,
        @Param("statuses") List<String> statuses,
        @Param("types") List<String> types,
        @Param("dealId") Integer dealId,
        @Param("memberScope") MemberScope memberScope,
        @Param("limit") int limit,
        @Param("offset") int offset);

    /**
     * The total row count the matching {@link #getWorkspacePage} page is drawn from.
     *
     * @param workspaceId the resolved tenant
     * @param query the escaped {@code LIKE} pattern over document title and deal name, or null
     * @param statuses the requested document statuses, or null for every status
     * @param types the requested document types, or null for every type
     * @param dealId one parent deal to restrict to, or null for every deal
     * @param memberScope the parent deal's ownership scope
     * @return the matching document count
     */
    long countWorkspace(
        @Param("workspaceId") int workspaceId,
        @Param("query") String query,
        @Param("statuses") List<String> statuses,
        @Param("types") List<String> types,
        @Param("dealId") Integer dealId,
        @Param("memberScope") MemberScope memberScope);

    /**
     * Bounded global-search slice of generated documents, matched on document title and deal name.
     *
     * @param workspaceId the resolved tenant
     * @param query the escaped {@code LIKE} pattern
     * @return at most ten matching documents, newest first
     */
    List<GeneratedDocumentSummaryDto> search(
        @Param("workspaceId") int workspaceId,
        @Param("query") String query);
    List<DealDocument> getByIds(@Param("workspaceId") int workspaceId,
        @Param("ids") List<Integer> ids);
    DealDocument getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    DealDocument lockById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Integer findDealIdById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Integer maxVersion(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    int countNonDraftByDeal(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    int insert(DealDocument document);
    int updateStatus(@Param("workspaceId") int workspaceId, @Param("id") int id, @Param("status") String status);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
