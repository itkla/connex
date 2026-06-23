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
import java.util.Set;

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
    private final AuditService auditService;
    private final WorkspaceService workspaceService;

    private static final Set<String> PIPELINE_AUDIT_FIELDS =
        Set.of("name");

    private static final Set<String> STAGE_AUDIT_FIELDS =
        Set.of("name", "position", "success", "failure");

    public List<Pipeline> getAllPipelines() {
        return pipelineMapper.getAllPipelines();
    }

    public Pipeline getPipelineById(int id) {
        Pipeline pipeline = pipelineMapper.getPipelineById(id);
        if (pipeline == null) throw new ResourceNotFoundException("Pipeline not found with id: " + id);
        hydrateStageDeals(pipeline.getStages());
        return pipeline;
    }

    public Pipeline createPipeline(Pipeline pipeline) {
        pipelineMapper.insertPipeline(pipeline);
        auditService.record("pipeline.create", "pipeline", pipeline.getId(), pipeline.getName(),
            "Created pipeline " + pipeline.getName(),
            auditService.diff(null, pipeline, PIPELINE_AUDIT_FIELDS));
        return pipeline;
    }

    public Pipeline updatePipeline(int id, Pipeline pipeline) {
        Pipeline before = pipelineMapper.getPipelineById(id);
        if (before == null) throw new ResourceNotFoundException("Pipeline not found with id: " + id);
        pipeline.setId(id);
        pipelineMapper.updatePipeline(pipeline);
        auditService.record("pipeline.update", "pipeline", id, pipeline.getName(),
            "Updated pipeline " + pipeline.getName(),
            auditService.diff(before, pipeline, PIPELINE_AUDIT_FIELDS));
        return pipeline;
    }

    public void deletePipeline(int id) {
        Pipeline before = pipelineMapper.getPipelineById(id);
        if (before == null) throw new ResourceNotFoundException("Pipeline not found with id: " + id);
        pipelineMapper.deletePipeline(id);
        auditService.record("pipeline.delete", "pipeline", id, before.getName(),
            "Deleted pipeline " + before.getName(),
            auditService.diff(before, null, PIPELINE_AUDIT_FIELDS));
    }

    // Stage operations (will likely move to separate StageService in the future)

    public List<Stage> getStagesByPipelineId(int pipelineId) {
        if (pipelineMapper.getPipelineById(pipelineId) == null) throw new ResourceNotFoundException("Pipeline not found with id: " + pipelineId);
        List<Stage> stages = pipelineMapper.getStagesByPipelineId(pipelineId);
        hydrateStageDeals(stages.toArray(Stage[]::new));
        return stages;
    }

    public Stage getStageById(int id) {
        Stage stage = pipelineMapper.getStageById(id);
        if (stage == null) throw new ResourceNotFoundException("Stage not found with id: " + id);
        stage.setDeals(dealMapper.getDealsByStageId(
            workspaceService.getCurrentWorkspaceId(),
            id
        ).toArray(Deal[]::new));
        return stage;
    }

    public Stage createStage(int pipelineId, Stage stage) {
        Pipeline pipeline = pipelineMapper.getPipelineById(pipelineId);
        if (pipeline == null) throw new ResourceNotFoundException("Pipeline not found with id: " + pipelineId);
        stage.setPipeline(pipeline);
        assertSingleTerminalOfType(pipelineId, stage);
        assertUniqueName(pipelineId, stage);
        pipelineMapper.insertStage(stage);
        auditService.record("stage.create", "stage", stage.getId(), stage.getName(),
            "Created stage " + stage.getName(),
            auditService.diff(null, stage, STAGE_AUDIT_FIELDS));
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
        auditService.record("stage.update", "stage", id, stage.getName(),
            "Updated stage " + stage.getName(),
            auditService.diff(existing, stage, STAGE_AUDIT_FIELDS));
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
        Stage before = pipelineMapper.getStageById(id);
        if (before == null) throw new ResourceNotFoundException("Stage not found with id: " + id);
        pipelineMapper.deleteStage(id);
        auditService.record("stage.delete", "stage", id, before.getName(),
            "Deleted stage " + before.getName(),
            auditService.diff(before, null, STAGE_AUDIT_FIELDS));
    }

    /**
     * Retrieves the deals associated with a pipeline.
     * @param pipelineId
     * @return
     */
    public List<Deal> getDealsByPipelineId(int pipelineId) {
        if (pipelineMapper.getPipelineById(pipelineId) == null) throw new ResourceNotFoundException("Pipeline not found with id: " + pipelineId);
        return dealMapper.getDealsByPipelineId(workspaceService.getCurrentWorkspaceId(), pipelineId);
    }

    /**
     * Retrieves the deals associated with a stage.
     * @param stageId
     * @return
     */
    public List<Deal> getDealsByStageId(int stageId) {
        if (pipelineMapper.getStageById(stageId) == null) throw new ResourceNotFoundException("Stage not found with id: " + stageId);
        return dealMapper.getDealsByStageId(workspaceService.getCurrentWorkspaceId(), stageId);
    }

    private void hydrateStageDeals(Stage[] stages) {
        if (stages == null) return;
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        for (Stage stage : stages) {
            stage.setDeals(dealMapper.getDealsByStageId(workspaceId, stage.getId()).toArray(Deal[]::new));
        }
    }
}
