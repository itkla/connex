package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

/** Workspace-scoped, source-checked maintenance of derived record search projections. */
public interface RecordSearchMapper {
    enum Kind { PERSON, COMPANY }

    record Row(int id, int workspaceId, String name, String phone) { }

    Integer maxId(@Param("workspaceId") int workspaceId, @Param("kind") Kind kind);

    List<Integer> pageIds(@Param("workspaceId") int workspaceId, @Param("kind") Kind kind,
        @Param("afterId") int afterId, @Param("throughId") int throughId, @Param("limit") int limit);

    List<Row> findMissing(@Param("workspaceId") int workspaceId, @Param("kind") Kind kind,
        @Param("afterId") int afterId, @Param("throughId") int throughId, @Param("limit") int limit);

    int reconcile(@Param("workspaceId") int workspaceId, @Param("kind") Kind kind,
        @Param("row") Row row, @Param("nameKey") String nameKey, @Param("phoneKey") String phoneKey);
}
