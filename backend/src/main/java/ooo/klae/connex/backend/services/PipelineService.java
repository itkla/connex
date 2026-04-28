package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import java.util.List;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for {@code Pipeline} and {@code Stage} management.
 * Handles mapping between {@code PipelineDto} and the {@code Pipeline}/{@code Stage} beans.
 * Delegates persistence to {@code PipelineMapper}.
 */

@Service
@RequiredArgsConstructor
public class PipelineService {
    private final PipelineMapper pipelineMapper;

    public List<Pipeline> getAllPipelines() {
        return pipelineMapper.getAllPipelines();
    }

    public Pipeline getPipelineById(int id) {
        Pipeline pipeline = pipelineMapper.getPipelineById(id);
        if (pipeline == null) throw new ResourceNotFoundException("Pipeline not found with id: " + id);
        return pipeline;
    }

    public Pipeline createPipeline(Pipeline pipeline) {
        pipelineMapper.insertPipeline(pipeline);
        return pipeline;
    }

    public Pipeline updatePipeline(int id, Pipeline pipeline) {
        if (pipelineMapper.getPipelineById(id) == null) throw new ResourceNotFoundException("Pipeline not found with id: " + id);
        pipeline.setId(id);
        pipelineMapper.updatePipeline(pipeline);
        return pipeline;
    }

    public void deletePipeline(int id) {
        if (pipelineMapper.getPipelineById(id) == null) throw new ResourceNotFoundException("Pipeline not found with id: " + id);
        pipelineMapper.deletePipeline(id);
    }

    // Stage operations (will likely move to separate StageService in the future)

    public List<Stage> getStagesByPipelineId(int pipelineId) {
        if (pipelineMapper.getPipelineById(pipelineId) == null) throw new ResourceNotFoundException("Pipeline not found with id: " + pipelineId);
        return pipelineMapper.getStagesByPipelineId(pipelineId);
    }

    public Stage getStageById(int id) {
        Stage stage = pipelineMapper.getStageById(id);
        if (stage == null) throw new ResourceNotFoundException("Stage not found with id: " + id);
        return stage;
    }

    public Stage createStage(int pipelineId, Stage stage) {
        Pipeline pipeline = pipelineMapper.getPipelineById(pipelineId);
        if (pipeline == null) throw new ResourceNotFoundException("Pipeline not found with id: " + pipelineId);
        stage.setPipeline(pipeline);
        pipelineMapper.insertStage(stage);
        return stage;
    }

    public Stage updateStage(int id, Stage stage) {
        if (pipelineMapper.getStageById(id) == null) throw new ResourceNotFoundException("Stage not found with id: " + id);
        stage.setId(id);
        pipelineMapper.updateStage(stage);
        return stage;
    }

    public void deleteStage(int id) {
        if (pipelineMapper.getStageById(id) == null) throw new ResourceNotFoundException("Stage not found with id: " + id);
        pipelineMapper.deleteStage(id);
    }
}
