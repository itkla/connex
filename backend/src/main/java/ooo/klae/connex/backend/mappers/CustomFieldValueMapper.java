package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.CustomFieldValue;
import java.util.List;

/**
 * Mapper for {@code CustomFieldValue} — polymorphic per-record values, joined with
 * their definitions on read. SQL is in {@code resources/mappers/CustomFieldValueMapper.xml}.
 * Every statement is scoped to the active workspace.
 */
public interface CustomFieldValueMapper {
    List<CustomFieldValue> getForEntity(@Param("workspaceId") int workspaceId,
        @Param("entityType") String entityType, @Param("entityId") int entityId);
    List<CustomFieldValue> getForEntities(@Param("workspaceId") int workspaceId,
        @Param("entityType") String entityType, @Param("entityIds") List<Integer> entityIds);
    int upsert(CustomFieldValue value);
    int deleteByDefinitionAndEntity(@Param("workspaceId") int workspaceId,
        @Param("definitionId") int definitionId, @Param("entityId") int entityId);
    int deleteByEntity(@Param("workspaceId") int workspaceId,
        @Param("entityType") String entityType, @Param("entityId") int entityId);
}
