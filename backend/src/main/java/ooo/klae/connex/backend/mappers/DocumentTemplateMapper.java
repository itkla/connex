package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.dto.DocumentTemplateSummaryDto;

/** Mapper for {@code document_template}; every statement is workspace-scoped. */
public interface DocumentTemplateMapper {
    List<DocumentTemplate> getAll(int workspaceId);

    /**
     * Bounded global-search slice of the template library, matched on name and type.
     *
     * @param workspaceId the resolved tenant
     * @param query the escaped {@code LIKE} pattern
     * @return at most ten matching templates, ordered by name
     */
    List<DocumentTemplateSummaryDto> search(
        @Param("workspaceId") int workspaceId,
        @Param("query") String query);

    DocumentTemplate getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int insert(DocumentTemplate template);
    int update(DocumentTemplate template);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
