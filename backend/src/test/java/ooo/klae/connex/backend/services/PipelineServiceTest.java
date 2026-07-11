package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

class PipelineServiceTest extends AbstractServiceTest {

    @Autowired PipelineService pipelineService;

    @Test
    void getDealsByPipelineId_returnsOnlyDealsInPipeline() {
        Pipeline pipelineA = newPipeline();
        Pipeline pipelineB = newPipeline();
        Stage stageA = newStage(pipelineA, 0);
        Stage stageB = newStage(pipelineB, 0);
        Deal dealA = newDeal(pipelineA, stageA, newCompany());
        Deal dealB = newDeal(pipelineB, stageB, newCompany());

        List<Deal> deals = pipelineService.getDealsByPipelineId(pipelineA.getId());

        assertTrue(deals.stream().anyMatch(x -> x.getId() == dealA.getId()));
        assertTrue(deals.stream().noneMatch(x -> x.getId() == dealB.getId()));
    }

    @Test
    void getDealsByPipelineId_throwsWhenPipelineMissing() {
        assertThrows(ResourceNotFoundException.class, () -> pipelineService.getDealsByPipelineId(-1));
    }

    @Test
    void getAllStagesReturnsOneWorkspaceScopedBatchWithoutDealHydration() {
        Pipeline first = newPipeline();
        Pipeline second = newPipeline();
        Stage firstStage = newStage(first, 0);
        Stage secondStage = newStage(second, 0);

        List<Stage> stages = pipelineService.getAllStages();

        assertTrue(stages.stream().anyMatch(stage -> stage.getId() == firstStage.getId()));
        assertTrue(stages.stream().anyMatch(stage -> stage.getId() == secondStage.getId()));
        assertTrue(stages.stream().allMatch(stage -> stage.getDeals() == null));
    }

    @Test
    void getDealsByStageId_returnsOnlyDealsInStage() {
        Pipeline pipeline = newPipeline();
        Stage stage1 = newStage(pipeline, 0);
        Stage stage2 = newStage(pipeline, 1);
        Deal deal1 = newDeal(pipeline, stage1, newCompany());
        Deal deal2 = newDeal(pipeline, stage2, newCompany());

        List<Deal> deals = pipelineService.getDealsByStageId(stage1.getId());

        assertTrue(deals.stream().anyMatch(x -> x.getId() == deal1.getId()));
        assertTrue(deals.stream().noneMatch(x -> x.getId() == deal2.getId()));
    }

    @Test
    void getDealsByStageId_throwsWhenStageMissing() {
        assertThrows(ResourceNotFoundException.class, () -> pipelineService.getDealsByStageId(-1));
    }
}
