package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import java.util.List;

/**
 * Mapper interface for {@code Pipeline} and {@code Stage} persistence.
 * SQL is defined in {@code resources/mappers/PipelineMapper.xml}.
 * Used by {@code PipelineService}.
 */

public interface PipelineMapper {
    List<Pipeline> getAllPipelines(int workspaceId);
    Pipeline getPipelineById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Pipeline getOwnedPipelineById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    /** Locks a visible pipeline before an import writes a dependent deal. */
    Integer lockVisiblePipelineById(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id);
    List<Pipeline> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    boolean pipelineExists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int insertPipeline(Pipeline pipeline);
    int updatePipeline(Pipeline pipeline);
    int deletePipeline(@Param("workspaceId") int workspaceId, @Param("id") int id);

    Stage getStageById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    /** Locks a visible stage before an import writes a dependent deal. */
    Integer lockVisibleStageById(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id);
    List<Stage> getAllStages(int workspaceId);
    /** A stage visible through ownership or a same-organization pipeline share. */
    Stage getVisibleStageById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Stage> getStagesByPipelineId(@Param("workspaceId") int workspaceId, @Param("pipelineId") int pipelineId);
    int insertStage(Stage stage);
    int updateStage(Stage stage);
    int deleteStage(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
