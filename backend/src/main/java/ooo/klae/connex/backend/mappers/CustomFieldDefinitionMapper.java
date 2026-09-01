package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import java.util.List;

/**
 * Mapper for {@code CustomFieldDefinition} persistence. SQL is defined in
 * {@code resources/mappers/CustomFieldDefinitionMapper.xml}. Every read/write is
 * scoped to the active workspace.
 */
public interface CustomFieldDefinitionMapper {
    List<CustomFieldDefinition> getAll(int workspaceId);
    List<CustomFieldDefinition> getByEntityType(@Param("workspaceId") int workspaceId, @Param("entityType") String entityType);
    List<CustomFieldDefinition> getActiveByEntityType(@Param("workspaceId") int workspaceId, @Param("entityType") String entityType);
    CustomFieldDefinition getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    CustomFieldDefinition getByIdForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);
    CustomFieldDefinition getByKey(@Param("workspaceId") int workspaceId, @Param("entityType") String entityType, @Param("fieldKey") String fieldKey);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int insert(CustomFieldDefinition definition);
    int update(CustomFieldDefinition definition);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
