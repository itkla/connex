package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.PipelineDto;
import ooo.klae.connex.backend.dto.PipelineStagesDto;
import ooo.klae.connex.backend.dto.StageDto;
import ooo.klae.connex.backend.services.PipelineService;

import java.util.List;

import jakarta.validation.Valid;
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
    public List<PipelineDto> getAllPipelines() {
        return pipelineService.getAllPipelines().stream().map(PipelineDto::from).toList();
    }

    /**
     * GET endpoint to retrieve a single pipeline by ID.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public PipelineDto getPipelineById(@PathVariable int id) {
        return PipelineDto.from(pipelineService.getPipelineById(id));
    }

    /**
     * POST endpoint to create a new pipeline.
     * @param dto
     * @return
     */
    @PostMapping
    public PipelineDto createPipeline(@Valid @RequestBody PipelineDto dto) {
        return PipelineDto.from(pipelineService.createPipeline(dto.toBean()));
    }

    /**
     * PUT endpoint to update an existing pipeline.
     * @param id
     * @param dto
     * @return
     */
    @PutMapping("/{id}")
    public PipelineDto updatePipeline(@PathVariable int id, @Valid @RequestBody PipelineDto dto) {
        return PipelineDto.from(pipelineService.updatePipeline(id, dto.toBean()));
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
     * GET endpoint to retrieve the stages of a pipeline, ordered by {@code position} ascending.
     * @param pipelineId
     * @return
     */
    @GetMapping("/{pipelineId}/stages")
    public List<StageDto> getStagesByPipelineId(@PathVariable int pipelineId) {
        return pipelineService.getStagesByPipelineId(pipelineId).stream().map(StageDto::from).toList();
    }

    /**
     * GET endpoint to retrieve every visible stage in one workspace-scoped query.
     */
    @GetMapping("/stages")
    public List<StageDto> getAllStages() {
        return pipelineService.getAllStages().stream().map(StageDto::from).toList();
    }

    /**
     * GET endpoint to retrieve a single stage by ID.
     * @param id
     * @return
     */
    @GetMapping("/stages/{id}")
    public StageDto getStageById(@PathVariable int id) {
        return StageDto.from(pipelineService.getStageById(id));
    }

    /**
     * POST endpoint to create a new stage within a pipeline.
     * @param pipelineId
     * @param dto
     * @return
     */
    @PostMapping("/{pipelineId}/stages")
    public StageDto createStage(@PathVariable int pipelineId, @Valid @RequestBody StageDto dto) {
        return StageDto.from(pipelineService.createStage(pipelineId, dto.toBean()));
    }

    /**
     * PUT endpoint to replace a pipeline's whole stage set in one transaction. Entries carrying an
     * id are kept and updated, entries without one are created, and any stage absent from the list
     * is removed; positions follow the order given. Validating the final set rather than each write
     * makes edits that are only valid as a whole — a name swap, or moving the Won flag — succeed.
     * @param pipelineId
     * @param dto the complete stage set the pipeline should end up with
     * @return the pipeline's stages after the replacement, in order
     */
    @PutMapping("/{pipelineId}/stages")
    public List<StageDto> replaceStages(@PathVariable int pipelineId, @Valid @RequestBody PipelineStagesDto dto) {
        return pipelineService
            .replaceStages(pipelineId, dto.getStages().stream().map(StageDto::toBean).toList())
            .stream().map(StageDto::from).toList();
    }

    /**
     * PUT endpoint to update an existing stage.
     * @param id
     * @param dto
     * @return
     */
    @PutMapping("/stages/{id}")
    public StageDto updateStage(@PathVariable int id, @Valid @RequestBody StageDto dto) {
        return StageDto.from(pipelineService.updateStage(id, dto.toBean()));
    }

    /**
     * DELETE endpoint to delete a stage by ID.
     * Cannot be deleted while deals reference it
     * ({@code ON DELETE RESTRICT} on {@code deal.stage_id}).
     * @param id
     */
    @DeleteMapping("/stages/{id}")
    public void deleteStage(@PathVariable int id) {
        pipelineService.deleteStage(id);
    }

    /**
     * GET endpoint to retrieve deals associated with a pipeline.
     * @param id
     * @return
     */
    @GetMapping("/{id}/deals")
    public List<DealDto> getDealsForPipeline(@PathVariable int id) {
        return pipelineService.getDealsByPipelineId(id).stream().map(DealDto::from).toList();
    }

    /**
     * GET endpoint to retrieve deals associated with a stage.
     * Useful for rendering a single column of a kanban board.
     * @param id
     * @return
     */
    @GetMapping("/stages/{id}/deals")
    public List<DealDto> getDealsForStage(@PathVariable int id) {
        return pipelineService.getDealsByStageId(id).stream().map(DealDto::from).toList();
    }
}
