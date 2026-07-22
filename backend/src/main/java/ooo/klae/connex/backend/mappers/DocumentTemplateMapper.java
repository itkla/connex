package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.DocumentTemplate;

/** Mapper for {@code document_template}; every statement is workspace-scoped. */
public interface DocumentTemplateMapper {
    List<DocumentTemplate> getAll(int workspaceId);
    DocumentTemplate getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int insert(DocumentTemplate template);
    int update(DocumentTemplate template);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
