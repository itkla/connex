package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ShareMapper;

class PipelineServiceTest extends AbstractServiceTest {

    @Autowired PipelineService pipelineService;
    @Autowired ShareMapper shareMapper;

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

    @Test
    void readOnlySharedPipelineCannotBeMutatedByTheGranteeWorkspace() {
        Pipeline pipeline = newPipeline();
        Stage sharedStage = newStage(pipeline, 0);
        Workspace sibling = new Workspace();
        sibling.setName("Sibling " + unique());
        sibling.setSlug("sibling_" + unique());
        sibling.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(sibling);
        workspaceMapper.addMember(sibling.getId(), currentUser.getId(), "owner");
        shareMapper.sharePipeline(
            pipeline.getId(), workspace.getId(), sibling.getId(), currentUser.getId(), false);
        workspace = sibling;
        authenticateAs(currentUser, sibling.getId());
        Deal granteeDeal = newDeal(pipeline, sharedStage, newCompany());

        Stage stage = new Stage();
        stage.setName("Injected");
        stage.setPosition(1);
        Pipeline update = new Pipeline();
        update.setName("Renamed");

        assertEquals(sharedStage.getId(), pipelineService.getStageById(sharedStage.getId()).getId());
        assertTrue(pipelineService.getDealsByStageId(sharedStage.getId()).stream()
            .anyMatch(deal -> deal.getId() == granteeDeal.getId()));
        assertThrows(ResourceNotFoundException.class,
            () -> pipelineService.createStage(pipeline.getId(), stage));
        assertThrows(ResourceNotFoundException.class,
            () -> pipelineService.updatePipeline(pipeline.getId(), update));
        assertThrows(ResourceNotFoundException.class,
            () -> pipelineService.deletePipeline(pipeline.getId()));
    }
}
