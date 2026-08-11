package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.EntityReference;
import java.util.List;

/**
 * Mapper interface for {@code EntityReference} persistence.
 * SQL is defined in {@code resources/mappers/EntityReferenceMapper.xml}.
 * Used by {@code ReferenceService}.
 */

public interface EntityReferenceMapper {
    List<EntityReference> findBySource(
        @Param("workspaceId") int workspaceId,
        @Param("sourceType") String sourceType,
        @Param("sourceId") int sourceId
    );

    List<EntityReference> findBySources(
        @Param("workspaceId") int workspaceId,
        @Param("sourceType") String sourceType,
        @Param("sourceIds") List<Integer> sourceIds
    );

    int insert(EntityReference reference);

    int deleteBySource(
        @Param("workspaceId") int workspaceId,
        @Param("sourceType") String sourceType,
        @Param("sourceId") int sourceId
    );

    int deleteBySourceIds(
        @Param("workspaceId") int workspaceId,
        @Param("sourceType") String sourceType,
        @Param("sourceIds") List<Integer> sourceIds
    );

    int deleteByTarget(
        @Param("workspaceId") int workspaceId,
        @Param("refType") String refType,
        @Param("refId") int refId
    );
}
