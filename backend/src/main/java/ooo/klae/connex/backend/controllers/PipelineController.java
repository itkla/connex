package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.services.PipelineService;

import java.util.List;

import lombok.RequiredArgsConstructor;

/**
 * REST controller for managing {@code Pipeline}s and their {@code Stage}s.
 * Accepts and returns {@code PipelineDto}. Delegates to {@code PipelineService}.
 */

@RestController
@RequestMapping("/api/pipelines")
@RequiredArgsConstructor
public class PipelineController {
    private final PipelineService pipelineService;

    /**
     * GET endpoint to retrieve all pipelines.
     * Note: this returns *all* pipelines, regardless of company/user/etc. 
     * @return
     */
    @GetMapping
    public List<Pipeline> getAllPipelines() {
        return pipelineService.getAllPipelines();
    }

    //TODO: add filtering by companyId, userId, etc. once those concepts are implemented

    /**
     * GET endpoint to retrieve a single pipeline by ID.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public Pipeline getPipelineById(@PathVariable int id) {
        return pipelineService.getPipelineById(id);
    }

    /**
     * POST endpoint to create a new pipeline.
     * @param pipeline
     * @return
     */
    @PostMapping
    public Pipeline createPipeline(@RequestBody Pipeline pipeline) {
        return pipelineService.createPipeline(pipeline);
    }

    /**
     * PUT endpoint to update an existing pipeline.
     * @param id
     * @param pipeline
     * @return
     */
    @PutMapping("/{id}")
    public Pipeline updatePipeline(@PathVariable int id, @RequestBody Pipeline pipeline) {
        return pipelineService.updatePipeline(id, pipeline);
    }

    /**
     * DELETE endpoint to delete a pipeline by ID.
     * @param id
     */
    @DeleteMapping("/{id}")
    public void deletePipeline(@PathVariable int id) {
        pipelineService.deletePipeline(id);
    }

    /**
     * GET endpoint to retrieve stages for a given pipeline ID.
     * @param pipelineId
     * @return
     */
    @GetMapping("/{pipelineId}/stages")
    public List<Stage> getStagesByPipelineId(@PathVariable int pipelineId) {
        return pipelineService.getStagesByPipelineId(pipelineId);
    }

    /**
     * GET endpoint to retrieve a single stage by ID.
     * @param id
     * @return
     */
    @GetMapping("/stages/{id}")
    public Stage getStageById(@PathVariable int id) {
        return pipelineService.getStageById(id);
    }

    /**
     * POST endpoint to create a new stage within a pipeline.
     * @param pipelineId
     * @param stage
     * @return
     */
    @PostMapping("/{pipelineId}/stages")
    public Stage createStage(@PathVariable int pipelineId, @RequestBody Stage stage) {
        return pipelineService.createStage(pipelineId, stage);
    }

    /**
     * PUT endpoint to update an existing stage.
     * @param id
     * @param stage
     * @return
     */
    @PutMapping("/stages/{id}")
    public Stage updateStage(@PathVariable int id, @RequestBody Stage stage) {
        return pipelineService.updateStage(id, stage);
    }

    /**
     * DELETE endpoint to delete a stage by ID.
     * @param id
     */
    @DeleteMapping("/stages/{id}")
    public void deleteStage(@PathVariable int id) {
        pipelineService.deleteStage(id);
    }
}
