package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for {@code Pipeline} and {@code Stage} management.
 * Every read/write is scoped to the caller's active workspace; a stage inherits
 * its workspace from its pipeline. Delegates persistence to {@code PipelineMapper}.
 */

@Service
@RequiredArgsConstructor
public class PipelineService {
    private final PipelineMapper pipelineMapper;
    private final DealMapper dealMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;
    private final ReferenceService referenceService;

    private static final Set<String> PIPELINE_AUDIT_FIELDS =
        Set.of("name");

    private static final Set<String> STAGE_AUDIT_FIELDS =
        Set.of("name", "position", "success", "failure");

    public List<Pipeline> getAllPipelines() {
        return pipelineMapper.getAllPipelines(workspaceService.getCurrentWorkspaceId());
    }

    public Pipeline getPipelineById(int id) {
        Pipeline pipeline = pipelineMapper.getPipelineById(workspaceService.getCurrentWorkspaceId(), id);
        if (pipeline == null) throw new ResourceNotFoundException("Pipeline not found with id: " + id);
        hydrateStageDeals(pipeline.getStages());
        return pipeline;
    }

    @RequirePermission(Permission.PIPELINE_MANAGE)
    public Pipeline createPipeline(Pipeline pipeline) {
        pipeline.setWorkspaceId(workspaceService.getCurrentWorkspaceId());
        pipelineMapper.insertPipeline(pipeline);
        auditService.record("pipeline.create", "pipeline", pipeline.getId(), pipeline.getName(),
            "Created pipeline " + pipeline.getName(),
            auditService.diff(null, pipeline, PIPELINE_AUDIT_FIELDS));
        return pipeline;
    }

    @RequirePermission(Permission.PIPELINE_MANAGE)
    public Pipeline updatePipeline(int id, Pipeline pipeline) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Pipeline before = requireOwnedPipeline(workspaceId, id);
        pipeline.setId(id);
        pipeline.setWorkspaceId(workspaceId);
        pipelineMapper.updatePipeline(pipeline);
        auditService.record("pipeline.update", "pipeline", id, pipeline.getName(),
            "Updated pipeline " + pipeline.getName(),
            auditService.diff(before, pipeline, PIPELINE_AUDIT_FIELDS));
        return pipeline;
    }

    @RequirePermission(Permission.PIPELINE_MANAGE)
    public void deletePipeline(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Pipeline before = requireOwnedPipeline(workspaceId, id);
        pipelineMapper.deletePipeline(workspaceId, id);
        auditService.record("pipeline.delete", "pipeline", id, before.getName(),
            "Deleted pipeline " + before.getName(),
            auditService.diff(before, null, PIPELINE_AUDIT_FIELDS));
    }

    // Stage operations (will likely move to separate StageService in the future)

    public List<Stage> getStagesByPipelineId(int pipelineId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requirePipeline(workspaceId, pipelineId);
        List<Stage> stages = pipelineMapper.getStagesByPipelineId(workspaceId, pipelineId);
        hydrateStageDeals(stages.toArray(Stage[]::new));
        return stages;
    }

    public Stage getStageById(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Stage stage = pipelineMapper.getVisibleStageById(workspaceId, id);
        if (stage == null) throw new ResourceNotFoundException("Stage not found with id: " + id);
        stage.setDeals(referenceService.hydrateDeals(
            workspaceId, dealMapper.getDealsByStageId(workspaceId, id)).toArray(Deal[]::new));
        return stage;
    }

    @RequirePermission(Permission.PIPELINE_MANAGE)
    public Stage createStage(int pipelineId, Stage stage) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Pipeline pipeline = requireOwnedPipeline(workspaceId, pipelineId);
        stage.setPipeline(pipeline);
        stage.setWorkspaceId(workspaceId);
        assertSingleTerminalOfType(workspaceId, pipelineId, stage);
        assertUniqueName(workspaceId, pipelineId, stage);
        pipelineMapper.insertStage(stage);
        auditService.record("stage.create", "stage", stage.getId(), stage.getName(),
            "Created stage " + stage.getName(),
            auditService.diff(null, stage, STAGE_AUDIT_FIELDS));
        return stage;
    }

    @RequirePermission(Permission.PIPELINE_MANAGE)
    public Stage updateStage(int id, Stage stage) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Stage existing = pipelineMapper.getStageById(workspaceId, id);
        if (existing == null) throw new ResourceNotFoundException("Stage not found with id: " + id);
        stage.setId(id);
        stage.setWorkspaceId(workspaceId);
        stage.setPipeline(existing.getPipeline());
        assertSingleTerminalOfType(workspaceId, existing.getPipeline().getId(), stage);
        assertUniqueName(workspaceId, existing.getPipeline().getId(), stage);
        pipelineMapper.updateStage(stage);
        auditService.record("stage.update", "stage", id, stage.getName(),
            "Updated stage " + stage.getName(),
            auditService.diff(existing, stage, STAGE_AUDIT_FIELDS));
        return stage;
    }

    private void assertUniqueName(int workspaceId, int pipelineId, Stage stage) {
        String name = stage.getName() == null ? "" : stage.getName().trim();
        if (name.isEmpty()) return;
        for (Stage sibling : pipelineMapper.getStagesByPipelineId(workspaceId, pipelineId)) {
            if (sibling.getId() == stage.getId()) continue;
            if (sibling.getName() != null && name.equalsIgnoreCase(sibling.getName().trim()))
                throw new DuplicateResourceException("name", "A stage with this name already exists in this pipeline");
        }
    }

    private void assertSingleTerminalOfType(int workspaceId, int pipelineId, Stage stage) {
        if (!stage.isSuccess() && !stage.isFailure()) return;
        for (Stage sibling : pipelineMapper.getStagesByPipelineId(workspaceId, pipelineId)) {
            if (sibling.getId() == stage.getId()) continue; // skip self on update
            if (stage.isSuccess() && sibling.isSuccess())
                throw new DuplicateResourceException("This pipeline already has a Won stage");
            if (stage.isFailure() && sibling.isFailure())
                throw new DuplicateResourceException("This pipeline already has a Lost stage");
        }
    }

    @RequirePermission(Permission.PIPELINE_MANAGE)
    public void deleteStage(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Stage before = pipelineMapper.getStageById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Stage not found with id: " + id);
        pipelineMapper.deleteStage(workspaceId, id);
        auditService.record("stage.delete", "stage", id, before.getName(),
            "Deleted stage " + before.getName(),
            auditService.diff(before, null, STAGE_AUDIT_FIELDS));
    }

    /**
     * Retrieves the deals associated with a pipeline in the active workspace.
     */
    public List<Deal> getDealsByPipelineId(int pipelineId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requirePipeline(workspaceId, pipelineId);
        return referenceService.hydrateDeals(
            workspaceId, dealMapper.getDealsByPipelineId(workspaceId, pipelineId));
    }

    /**
     * Retrieves the deals associated with a stage in the active workspace.
     */
    public List<Deal> getDealsByStageId(int stageId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (pipelineMapper.getVisibleStageById(workspaceId, stageId) == null)
            throw new ResourceNotFoundException("Stage not found with id: " + stageId);
        return referenceService.hydrateDeals(
            workspaceId, dealMapper.getDealsByStageId(workspaceId, stageId));
    }

    private Pipeline requirePipeline(int workspaceId, int pipelineId) {
        Pipeline pipeline = pipelineMapper.getPipelineById(workspaceId, pipelineId);
        if (pipeline == null) throw new ResourceNotFoundException("Pipeline not found with id: " + pipelineId);
        return pipeline;
    }

    private Pipeline requireOwnedPipeline(int workspaceId, int pipelineId) {
        Pipeline pipeline = pipelineMapper.getOwnedPipelineById(workspaceId, pipelineId);
        if (pipeline == null) throw new ResourceNotFoundException("Pipeline not found with id: " + pipelineId);
        return pipeline;
    }

    private void hydrateStageDeals(Stage[] stages) {
        if (stages == null) return;
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Deal> deals = new ArrayList<>();
        for (Stage stage : stages) {
            List<Deal> stageDeals = dealMapper.getDealsByStageId(workspaceId, stage.getId());
            stage.setDeals(stageDeals.toArray(Deal[]::new));
            deals.addAll(stageDeals);
        }
        referenceService.hydrateDeals(workspaceId, deals);
    }
}
