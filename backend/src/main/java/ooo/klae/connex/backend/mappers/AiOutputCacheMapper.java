package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiOutputCache;

/**
 * Mapper for {@code AiOutputCache} — persisted AI feature outputs. SQL lives in
 * {@code resources/mappers/AiOutputCacheMapper.xml}. Every statement is workspace-scoped. There is
 * at most one row per {@code (workspace_id, feature, subject_a_id, subject_b_id)}, so writes go
 * through {@code upsert}; deal-scoped features pass {@code 0} for {@code subjectBId}.
 */
public interface AiOutputCacheMapper {
    AiOutputCache getBySubject(
            @Param("workspaceId") int workspaceId,
            @Param("feature") String feature,
            @Param("subjectAId") int subjectAId,
            @Param("subjectBId") int subjectBId);

    int upsert(AiOutputCache entry);
}
