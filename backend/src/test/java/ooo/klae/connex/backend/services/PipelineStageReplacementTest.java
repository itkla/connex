package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

/**
 * Covers replacing a pipeline's stage set in one transaction: the edits that per-stage writes cannot
 * express, and the guards that keep the final set valid.
 */
class PipelineStageReplacementTest extends AbstractServiceTest {

    @Autowired PipelineService pipelineService;

    private Stage keep(Stage existing, String name) {
        Stage stage = new Stage();
        stage.setId(existing.getId());
        stage.setName(name);
        stage.setSuccess(existing.isSuccess());
        stage.setFailure(existing.isFailure());
        return stage;
    }

    private Stage add(String name) {
        Stage stage = new Stage();
        stage.setName(name);
        return stage;
    }

    private Stage terminal(Stage existing, String name, boolean success, boolean failure) {
        Stage stage = keep(existing, name);
        stage.setSuccess(success);
        stage.setFailure(failure);
        return stage;
    }

    @Test
    void swapsTwoStageNamesInOneEdit() {
        Pipeline pipeline = newPipeline();
        Stage alpha = newStage(pipeline, 0);
        Stage beta = newStage(pipeline, 1);

        List<Stage> result = pipelineService.replaceStages(
            pipeline.getId(),
            List.of(keep(alpha, beta.getName()), keep(beta, alpha.getName())));

        assertEquals(2, result.size());
        assertEquals(beta.getName(), result.get(0).getName());
        assertEquals(alpha.getName(), result.get(1).getName());
        assertEquals(alpha.getId(), result.get(0).getId());
        assertEquals(beta.getId(), result.get(1).getId());
    }

    @Test
    void movesTheWonFlagBetweenStagesInOneEdit() {
        Pipeline pipeline = newPipeline();
        Stage first = newStage(pipeline, 0);
        Stage second = newStage(pipeline, 1);
        pipelineService.replaceStages(
            pipeline.getId(),
            List.of(terminal(first, first.getName(), true, false), keep(second, second.getName())));

        List<Stage> result = pipelineService.replaceStages(
            pipeline.getId(),
            List.of(keep(first, first.getName()), terminal(second, second.getName(), true, false)));

        assertTrue(result.stream().noneMatch(stage -> stage.getId() == first.getId() && stage.isSuccess()));
        assertTrue(result.stream().anyMatch(stage -> stage.getId() == second.getId() && stage.isSuccess()));
    }

    @Test
    void renumbersPositionsToTheOrderGiven() {
        Pipeline pipeline = newPipeline();
        Stage first = newStage(pipeline, 0);
        Stage second = newStage(pipeline, 7);

        List<Stage> result = pipelineService.replaceStages(
            pipeline.getId(),
            List.of(keep(second, second.getName()), keep(first, first.getName())));

        assertEquals(second.getId(), result.get(0).getId());
        assertEquals(0, result.get(0).getPosition());
        assertEquals(first.getId(), result.get(1).getId());
        assertEquals(1, result.get(1).getPosition());
    }

    @Test
    void createsAddedStagesAndRemovesAbsentOnes() {
        Pipeline pipeline = newPipeline();
        Stage kept = newStage(pipeline, 0);
        Stage dropped = newStage(pipeline, 1);

        List<Stage> result = pipelineService.replaceStages(
            pipeline.getId(),
            List.of(keep(kept, kept.getName()), add("Brand new")));

        assertEquals(2, result.size());
        assertTrue(result.stream().noneMatch(stage -> stage.getId() == dropped.getId()));
        assertTrue(result.stream().anyMatch(stage -> "Brand new".equals(stage.getName())));
        assertEquals(2, pipelineService.getStagesByPipelineId(pipeline.getId()).size());
    }

    @Test
    void emptiesAPipelineThatHasNoDeals() {
        Pipeline pipeline = newPipeline();
        newStage(pipeline, 0);

        assertEquals(List.of(), pipelineService.replaceStages(pipeline.getId(), List.of()));
        assertEquals(0, pipelineService.getStagesByPipelineId(pipeline.getId()).size());
    }

    @Test
    void refusesToRemoveAStageThatStillHoldsDeals() {
        Pipeline pipeline = newPipeline();
        Stage occupied = newStage(pipeline, 0);
        newDeal(pipeline, occupied, newCompany());

        assertThrows(BadRequestException.class,
            () -> pipelineService.replaceStages(pipeline.getId(), List.of()));
    }

    @Test
    void rejectsDuplicateNamesRegardlessOfCase() {
        Pipeline pipeline = newPipeline();
        Stage first = newStage(pipeline, 0);
        Stage second = newStage(pipeline, 1);

        assertThrows(DuplicateResourceException.class, () -> pipelineService.replaceStages(
            pipeline.getId(),
            List.of(keep(first, "Review"), keep(second, "review"))));
    }

    @Test
    void rejectsABlankStageName() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        assertThrows(BadRequestException.class, () -> pipelineService.replaceStages(
            pipeline.getId(), List.of(keep(stage, "   "))));
    }

    @Test
    void rejectsTwoWonStages() {
        Pipeline pipeline = newPipeline();
        Stage first = newStage(pipeline, 0);
        Stage second = newStage(pipeline, 1);

        assertThrows(DuplicateResourceException.class, () -> pipelineService.replaceStages(
            pipeline.getId(),
            List.of(terminal(first, first.getName(), true, false),
                    terminal(second, second.getName(), true, false))));
    }

    @Test
    void reversesAWholePipelineWithoutCollidingOnPosition() {
        Pipeline pipeline = newPipeline();
        Stage first = newStage(pipeline, 0);
        Stage second = newStage(pipeline, 1);
        Stage third = newStage(pipeline, 2);

        List<Stage> result = pipelineService.replaceStages(
            pipeline.getId(),
            List.of(keep(third, third.getName()), keep(second, second.getName()), keep(first, first.getName())));

        assertEquals(List.of(third.getId(), second.getId(), first.getId()),
            result.stream().map(Stage::getId).toList());
        assertEquals(List.of(0, 1, 2), result.stream().map(Stage::getPosition).toList());
    }

    @Test
    void rejectsAStageThatIsBothWonAndLost() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        assertThrows(BadRequestException.class, () -> pipelineService.replaceStages(
            pipeline.getId(), List.of(terminal(stage, stage.getName(), true, true))));
    }

    @Test
    void rejectsTwoLostStages() {
        Pipeline pipeline = newPipeline();
        Stage first = newStage(pipeline, 0);
        Stage second = newStage(pipeline, 1);

        assertThrows(DuplicateResourceException.class, () -> pipelineService.replaceStages(
            pipeline.getId(),
            List.of(terminal(first, first.getName(), false, true),
                    terminal(second, second.getName(), false, true))));
    }

    @Test
    void rejectsTheSameStageListedTwice() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);

        assertThrows(BadRequestException.class, () -> pipelineService.replaceStages(
            pipeline.getId(), List.of(keep(stage, "One"), keep(stage, "Two"))));
    }

    @Test
    void refusesAStageBelongingToAnotherPipeline() {
        Pipeline pipeline = newPipeline();
        Pipeline other = newPipeline();
        Stage foreign = newStage(other, 0);

        assertThrows(ResourceNotFoundException.class, () -> pipelineService.replaceStages(
            pipeline.getId(), List.of(keep(foreign, foreign.getName()))));
    }

    @Test
    void refusesAPipelineOutsideTheWorkspace() {
        assertThrows(ResourceNotFoundException.class,
            () -> pipelineService.replaceStages(-1, List.of()));
    }
}
