package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Workspace;

class PipelineMapperTest extends AbstractMapperTest {

    /**
     * Inserts a new pipeline and checks if the generated ID is not zero.
     */
    @Test
    void insertPipeline_assignsGeneratedId() {
        Pipeline pipeline = newPipeline();
        assertNotEquals(0, pipeline.getId());
    }

    /**
     * Gets a pipeline by ID and checks if the returned pipeline is not null.
     */
    @Test
    void getPipelineById_returnsInsertedRow() {
        Pipeline pipeline = newPipeline();

        Pipeline found = pipelineMapper.getPipelineById(workspace.getId(), pipeline.getId());

        assertNotNull(found);
        assertEquals(workspace.getId(), found.getWorkspaceId());
        assertEquals(pipeline.getName(), found.getName());
    }

    /**
     * Gets all pipelines and checks if the returned list includes the inserted pipeline.
     */
    @Test
    void getAllPipelines_includesInsertedRow() {
        Pipeline pipeline = newPipeline();

        List<Pipeline> all = pipelineMapper.getAllPipelines(workspace.getId());

        assertTrue(all.stream().anyMatch(x -> x.getId() == pipeline.getId()));
    }

    /**
     * Updates a pipeline and checks if the new values are persisted.
     */
    @Test
    void updatePipeline_persistsNewName() {
        Pipeline pipeline = newPipeline();
        pipeline.setName("Renamed Pipeline");

        pipelineMapper.updatePipeline(pipeline);

        assertEquals("Renamed Pipeline", pipelineMapper.getPipelineById(workspace.getId(), pipeline.getId()).getName());
    }

    /**
     * Deletes a pipeline and checks if the pipeline is removed.
     */
    @Test
    void deletePipeline_removesRow() {
        Pipeline pipeline = newPipeline();

        pipelineMapper.deletePipeline(workspace.getId(), pipeline.getId());

        assertNull(pipelineMapper.getPipelineById(workspace.getId(), pipeline.getId()));
    }

    /**
     * Inserts a new Stage and checks if the generated ID is not zero.
     */
    @Test
    void insertStage_assignsGeneratedId() {
        Pipeline pipeline = newPipeline();

        Stage stage = newStage(pipeline, 0);

        assertNotEquals(0, stage.getId());
    }

    /**
     * Gets a stage by ID and checks if the returned stage is not null.
     */
    @Test
    void getStageById_returnsInsertedRow() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 2);

        Stage found = pipelineMapper.getStageById(workspace.getId(), stage.getId());

        assertNotNull(found);
        assertEquals(stage.getName(), found.getName());
        assertEquals(2, found.getPosition());
        assertNotNull(found.getPipeline());
        assertEquals(pipeline.getId(), found.getPipeline().getId());
    }

    /**
     * Gets stages by pipeline ID and checks if the returned list includes the inserted stage.
     */
    @Test
    void getStagesByPipelineId_returnsStagesOrderedByPosition() {
        Pipeline pipeline = newPipeline();
        Stage stage2 = newStage(pipeline, 2);
        Stage stage0 = newStage(pipeline, 0);
        Stage stage1 = newStage(pipeline, 1);

        List<Stage> stages = pipelineMapper.getStagesByPipelineId(workspace.getId(), pipeline.getId());

        assertEquals(3, stages.size());
        assertEquals(stage0.getId(), stages.get(0).getId());
        assertEquals(stage1.getId(), stages.get(1).getId());
        assertEquals(stage2.getId(), stages.get(2).getId());
    }

    /**
     * Updates a stage and checks if the new values are persisted.
     */
    @Test
    void updateStage_persistsNewValues() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        stage.setName("Renamed Stage");
        stage.setPosition(5);

        pipelineMapper.updateStage(stage);

        Stage found = pipelineMapper.getStageById(workspace.getId(), stage.getId());
        assertEquals("Renamed Stage", found.getName());
        assertEquals(5, found.getPosition());
    }

    /**
     * Stages default to non-terminal, and the success/failure flags round-trip through update.
     */
    @Test
    void stageTerminalFlags_defaultFalseAndPersist() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        Stage fresh = pipelineMapper.getStageById(workspace.getId(), stage.getId());
        assertFalse(fresh.isSuccess());
        assertFalse(fresh.isFailure());

        stage.setSuccess(true);
        pipelineMapper.updateStage(stage);

        Stage updated = pipelineMapper.getStageById(workspace.getId(), stage.getId());
        assertTrue(updated.isSuccess());
        assertFalse(updated.isFailure());
    }

    /**
     * Deletes a stage and checks if the stage is removed.
     */
    @Test
    void deleteStage_removesRow() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        pipelineMapper.deleteStage(workspace.getId(), stage.getId());

        assertNull(pipelineMapper.getStageById(workspace.getId(), stage.getId()));
    }

    /**
     * Deletes a pipeline and checks if the pipeline is removed and all associated stages are also removed.
     */
    @Test
    void deletePipeline_cascadesToStages() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        pipelineMapper.deletePipeline(workspace.getId(), pipeline.getId());

        assertNull(pipelineMapper.getStageById(workspace.getId(), stage.getId()));
    }

    /**
     * A pipeline and its stages in another workspace are invisible and immutable from this workspace.
     */
    @Test
    void pipelinesAndStages_areIsolatedByWorkspace() {
        Pipeline mine = newPipeline();
        Workspace other = newWorkspace();
        Pipeline foreignPipeline = newPipelineIn(other);
        Stage foreignStage = newStageIn(other, foreignPipeline, 0);

        assertNull(pipelineMapper.getPipelineById(workspace.getId(), foreignPipeline.getId()));
        assertFalse(pipelineMapper.pipelineExists(workspace.getId(), foreignPipeline.getId()));
        assertNull(pipelineMapper.getStageById(workspace.getId(), foreignStage.getId()));
        assertTrue(pipelineMapper.getStagesByPipelineId(workspace.getId(), foreignPipeline.getId()).isEmpty());
        assertTrue(pipelineMapper.getAllPipelines(workspace.getId()).stream().noneMatch(p -> p.getId() == foreignPipeline.getId()));
        assertTrue(pipelineMapper.getAllPipelines(workspace.getId()).stream().anyMatch(p -> p.getId() == mine.getId()));

        // cross-workspace mutation affects zero rows; the foreign rows survive
        assertEquals(0, pipelineMapper.deletePipeline(workspace.getId(), foreignPipeline.getId()));
        assertTrue(pipelineMapper.pipelineExists(other.getId(), foreignPipeline.getId()));
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }

    private Pipeline newPipelineIn(Workspace ws) {
        Pipeline pipeline = new Pipeline();
        pipeline.setName("Pipeline " + unique());
        pipeline.setWorkspaceId(ws.getId());
        pipelineMapper.insertPipeline(pipeline);
        return pipeline;
    }

    private Stage newStageIn(Workspace ws, Pipeline pipeline, int position) {
        Stage stage = new Stage();
        stage.setName("Stage " + unique());
        stage.setWorkspaceId(ws.getId());
        stage.setPipeline(pipeline);
        stage.setPosition(position);
        pipelineMapper.insertStage(stage);
        return stage;
    }
}
