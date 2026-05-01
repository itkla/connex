package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;

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

        Pipeline found = pipelineMapper.getPipelineById(pipeline.getId());

        assertNotNull(found);
        assertEquals(pipeline.getName(), found.getName());
    }

    /**
     * Gets all pipelines and checks if the returned list includes the inserted pipeline.
     */
    @Test
    void getAllPipelines_includesInsertedRow() {
        Pipeline pipeline = newPipeline();

        List<Pipeline> all = pipelineMapper.getAllPipelines();

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

        assertEquals("Renamed Pipeline", pipelineMapper.getPipelineById(pipeline.getId()).getName());
    }

    /**
     * Deletes a pipeline and checks if the pipeline is removed.
     */
    @Test
    void deletePipeline_removesRow() {
        Pipeline pipeline = newPipeline();

        pipelineMapper.deletePipeline(pipeline.getId());

        assertNull(pipelineMapper.getPipelineById(pipeline.getId()));
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

        Stage found = pipelineMapper.getStageById(stage.getId());

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

        List<Stage> stages = pipelineMapper.getStagesByPipelineId(pipeline.getId());

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

        Stage found = pipelineMapper.getStageById(stage.getId());
        assertEquals("Renamed Stage", found.getName());
        assertEquals(5, found.getPosition());
    }

    /**
     * Deletes a stage and checks if the stage is removed.
     */
    @Test
    void deleteStage_removesRow() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        pipelineMapper.deleteStage(stage.getId());

        assertNull(pipelineMapper.getStageById(stage.getId()));
    }

    /**
     * Deletes a pipeline and checks if the pipeline is removed and all associated stages are also removed.
     */
    @Test
    void deletePipeline_cascadesToStages() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        pipelineMapper.deletePipeline(pipeline.getId());

        assertNull(pipelineMapper.getStageById(stage.getId()));
    }
}
