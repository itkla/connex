package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
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
    private final DealMapper dealMapper;

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
        assertSingleTerminalOfType(pipelineId, stage);
        assertUniqueName(pipelineId, stage);
        pipelineMapper.insertStage(stage);
        return stage;
    }

    public Stage updateStage(int id, Stage stage) {
        Stage existing = pipelineMapper.getStageById(id);
        if (existing == null) throw new ResourceNotFoundException("Stage not found with id: " + id);
        stage.setId(id);
        stage.setPipeline(existing.getPipeline());
        assertSingleTerminalOfType(existing.getPipeline().getId(), stage);
        assertUniqueName(existing.getPipeline().getId(), stage);
        pipelineMapper.updateStage(stage);
        return stage;
    }

    private void assertUniqueName(int pipelineId, Stage stage) {
        String name = stage.getName() == null ? "" : stage.getName().trim();
        if (name.isEmpty()) return;
        for (Stage sibling : pipelineMapper.getStagesByPipelineId(pipelineId)) {
            if (sibling.getId() == stage.getId()) continue;
            if (sibling.getName() != null && name.equalsIgnoreCase(sibling.getName().trim()))
                throw new DuplicateResourceException("name", "A stage with this name already exists in this pipeline");
        }
    }

    private void assertSingleTerminalOfType(int pipelineId, Stage stage) {
        if (!stage.isSuccess() && !stage.isFailure()) return;
        for (Stage sibling : pipelineMapper.getStagesByPipelineId(pipelineId)) {
            if (sibling.getId() == stage.getId()) continue; // skip self on update
            if (stage.isSuccess() && sibling.isSuccess())
                throw new DuplicateResourceException("This pipeline already has a Won stage");
            if (stage.isFailure() && sibling.isFailure())
                throw new DuplicateResourceException("This pipeline already has a Lost stage");
        }
    }

    public void deleteStage(int id) {
        if (pipelineMapper.getStageById(id) == null) throw new ResourceNotFoundException("Stage not found with id: " + id);
        pipelineMapper.deleteStage(id);
    }

    /**
     * Retrieves the deals associated with a pipeline.
     * @param pipelineId
     * @return
     */
    public List<Deal> getDealsByPipelineId(int pipelineId) {
        if (pipelineMapper.getPipelineById(pipelineId) == null) throw new ResourceNotFoundException("Pipeline not found with id: " + pipelineId);
        return dealMapper.getDealsByPipelineId(pipelineId);
    }

    /**
     * Retrieves the deals associated with a stage.
     * @param stageId
     * @return
     */
    public List<Deal> getDealsByStageId(int stageId) {
        if (pipelineMapper.getStageById(stageId) == null) throw new ResourceNotFoundException("Stage not found with id: " + stageId);
        return dealMapper.getDealsByStageId(stageId);
    }
}
