package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    public List<Stage> getAllStages() {
        return pipelineMapper.getAllStages(workspaceService.getCurrentWorkspaceId());
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

    /**
     * Replaces a pipeline's stages with {@code requested} in one transaction: entries carrying an id
     * are kept and updated, entries without one are created, and any stage of the pipeline absent
     * from the list is removed. Positions are renumbered to the given order.
     *
     * <p>Validation runs against the final set rather than each write, so an edit that is only ever
     * valid as a whole — swapping two stage names, or moving the Won flag from one stage to another —
     * succeeds here even though the same edit expressed as a sequence of single-stage writes cannot.
     * A removal that still holds deals is refused up front, in place of the foreign key violation the
     * per-stage delete surfaces.
     *
     * <p>Renumbering happens in two passes because {@code (pipeline_id, position)} is unique: a stage
     * cannot move into a position another one still holds. Every surviving stage is first parked above
     * the range the final order occupies, then renumbered down into it.
     */
    @Transactional
    @RequirePermission(Permission.PIPELINE_MANAGE)
    public List<Stage> replaceStages(int pipelineId, List<Stage> requested) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Pipeline pipeline = requireOwnedPipeline(workspaceId, pipelineId);
        List<Stage> incoming = requested == null ? List.of() : requested;

        assertNamesDistinct(incoming);
        assertAtMostOneTerminalPerType(incoming);

        List<Stage> existing = pipelineMapper.getStagesByPipelineId(workspaceId, pipelineId);
        Map<Integer, Stage> existingById = new HashMap<>();
        for (Stage stage : existing) existingById.put(stage.getId(), stage);

        Set<Integer> keptIds = new HashSet<>();
        for (Stage stage : incoming) {
            if (stage.getId() == 0) continue;
            if (!existingById.containsKey(stage.getId()))
                throw new ResourceNotFoundException("Stage not found with id: " + stage.getId());
            if (!keptIds.add(stage.getId()))
                throw new BadRequestException("Stage " + stage.getId() + " is listed more than once");
        }

        for (Stage stage : existing) {
            if (keptIds.contains(stage.getId())) continue;
            if (!dealMapper.getDealsByStageId(workspaceId, stage.getId()).isEmpty())
                throw new BadRequestException(
                    "Move the deals out of " + stage.getName() + " before removing it");
        }

        for (Stage stage : existing) {
            if (keptIds.contains(stage.getId())) continue;
            pipelineMapper.deleteStage(workspaceId, stage.getId());
            auditService.record("stage.delete", "stage", stage.getId(), stage.getName(),
                "Deleted stage " + stage.getName(),
                auditService.diff(stage, null, STAGE_AUDIT_FIELDS));
        }

        int parked = incoming.size();
        for (Stage stage : existing) parked = Math.max(parked, stage.getPosition() + 1);
        for (Stage stage : incoming) {
            if (stage.getId() == 0) continue;
            pipelineMapper.updateStage(reposition(existingById.get(stage.getId()), pipeline, workspaceId, parked++));
        }

        List<Stage> result = new ArrayList<>();
        int position = 0;
        for (Stage stage : incoming) {
            stage.setWorkspaceId(workspaceId);
            stage.setPipeline(pipeline);
            stage.setPosition(position++);
            if (stage.getId() == 0) {
                pipelineMapper.insertStage(stage);
                auditService.record("stage.create", "stage", stage.getId(), stage.getName(),
                    "Created stage " + stage.getName(),
                    auditService.diff(null, stage, STAGE_AUDIT_FIELDS));
            } else {
                Stage before = existingById.get(stage.getId());
                pipelineMapper.updateStage(stage);
                auditService.record("stage.update", "stage", stage.getId(), stage.getName(),
                    "Updated stage " + stage.getName(),
                    auditService.diff(before, stage, STAGE_AUDIT_FIELDS));
            }
            result.add(stage);
        }
        return result;
    }

    /**
     * A copy of {@code stage} parked at {@code position}, leaving the caller's snapshot — which the
     * audit diff reads as the "before" — untouched.
     */
    private Stage reposition(Stage stage, Pipeline pipeline, int workspaceId, int position) {
        Stage parked = new Stage();
        parked.setId(stage.getId());
        parked.setName(stage.getName());
        parked.setSuccess(stage.isSuccess());
        parked.setFailure(stage.isFailure());
        parked.setPipeline(pipeline);
        parked.setWorkspaceId(workspaceId);
        parked.setPosition(position);
        return parked;
    }

    private void assertNamesDistinct(List<Stage> stages) {
        Set<String> seen = new HashSet<>();
        for (Stage stage : stages) {
            String name = stage.getName() == null ? "" : stage.getName().trim();
            if (name.isEmpty()) throw new BadRequestException("Every stage needs a name");
            if (!seen.add(name.toLowerCase(Locale.ROOT)))
                throw new DuplicateResourceException("name", "A stage with this name already exists in this pipeline");
        }
    }

    private void assertAtMostOneTerminalPerType(List<Stage> stages) {
        boolean success = false;
        boolean failure = false;
        for (Stage stage : stages) {
            if (stage.isSuccess() && stage.isFailure())
                throw new BadRequestException("A stage cannot be both the Won and the Lost stage");
            if (stage.isSuccess()) {
                if (success) throw new DuplicateResourceException("This pipeline already has a Won stage");
                success = true;
            }
            if (stage.isFailure()) {
                if (failure) throw new DuplicateResourceException("This pipeline already has a Lost stage");
                failure = true;
            }
        }
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
