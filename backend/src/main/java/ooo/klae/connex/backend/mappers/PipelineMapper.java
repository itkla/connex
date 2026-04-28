package ooo.klae.connex.backend.mappers;

import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import java.util.List;

/**
 * Mapper interface for {@code Pipeline} and {@code Stage} persistence.
 * SQL is defined in {@code resources/mappers/PipelineMapper.xml}.
 * Used by {@code PipelineService}.
 */

public interface PipelineMapper {
    List<Pipeline> getAllPipelines();
    Pipeline getPipelineById(int id);
    int insertPipeline(Pipeline pipeline);
    int updatePipeline(Pipeline pipeline);
    int deletePipeline(int id);

    Stage getStageById(int id);
    List<Stage> getStagesByPipelineId(int pipelineId);
    int insertStage(Stage stage);
    int updateStage(Stage stage);
    int deleteStage(int id);
}
