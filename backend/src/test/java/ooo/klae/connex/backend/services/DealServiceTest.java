package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.DealSummaryDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

class DealServiceTest extends AbstractServiceTest {

    @Autowired DealService dealService;

    @Test
    void getActivitiesByDealId_returnsOnlyMatchingActivities() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal d1 = newDeal(pipeline, stage, company);
        Deal d2 = newDeal(pipeline, stage, company);
        User user = newUser();
        Activity a1 = newActivity(user, null, d1);
        Activity a2 = newActivity(user, null, d2);

        List<Activity> activities = dealService.getActivitiesByDealId(d1.getId());

        assertTrue(activities.stream().anyMatch(x -> x.getId() == a1.getId()));
        assertTrue(activities.stream().noneMatch(x -> x.getId() == a2.getId()));
    }

    @Test
    void getActivitiesByDealId_throwsWhenDealMissing() {
        assertThrows(ResourceNotFoundException.class, () -> dealService.getActivitiesByDealId(-1));
    }

    @Test
    void getNotesByDealId_returnsOnlyMatchingNotes() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal d1 = newDeal(pipeline, stage, company);
        Deal d2 = newDeal(pipeline, stage, company);
        User user = newUser();
        Note n1 = newNote(user, null, d1);
        Note n2 = newNote(user, null, d2);

        List<Note> notes = dealService.getNotesByDealId(d1.getId());

        assertTrue(notes.stream().anyMatch(x -> x.getId() == n1.getId()));
        assertTrue(notes.stream().noneMatch(x -> x.getId() == n2.getId()));
    }

    @Test
    void getNotesByDealId_throwsWhenDealMissing() {
        assertThrows(ResourceNotFoundException.class, () -> dealService.getNotesByDealId(-1));
    }

    @Test
    void getTasksByDealId_returnsOnlyMatchingTasks() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal d1 = newDeal(pipeline, stage, company);
        Deal d2 = newDeal(pipeline, stage, company);
        User user = newUser();
        Task t1 = newTask(user, null, d1);
        Task t2 = newTask(user, null, d2);

        List<Task> tasks = dealService.getTasksByDealId(d1.getId());

        assertTrue(tasks.stream().anyMatch(x -> x.getId() == t1.getId()));
        assertTrue(tasks.stream().noneMatch(x -> x.getId() == t2.getId()));
    }

    @Test
    void getTasksByDealId_throwsWhenDealMissing() {
        assertThrows(ResourceNotFoundException.class, () -> dealService.getTasksByDealId(-1));
    }

    @Test
    void getDealSummary_resolvesNames() {
        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);

        DealSummaryDto summary = dealService.getDealSummary(deal.getId());

        assertEquals(deal.getId(), summary.getId());
        assertEquals(pipeline.getName(), summary.getPipelineName());
        assertEquals(stage.getName(), summary.getStageName());
        assertEquals(company.getName(), summary.getCompanyName());
        assertEquals(currentUser.getDisplayName(), summary.getOwnerName());
        assertEquals("open", summary.getStatus());
    }

    @Test
    void getDealSummary_throwsWhenDealMissing() {
        assertThrows(ResourceNotFoundException.class, () -> dealService.getDealSummary(-1));
    }

    @Test
    void move_reordersWithinStageContiguously() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal d1 = newDeal(pipeline, stage, company);
        Deal d2 = newDeal(pipeline, stage, company);
        Deal d3 = newDeal(pipeline, stage, company);

        dealService.move(d3.getId(), stage.getId(), 0);

        List<Deal> column = dealService.getDealsByStageId(stage.getId());
        assertEquals(List.of(d3.getId(), d1.getId(), d2.getId()),
            column.stream().map(Deal::getId).toList());
        assertEquals(List.of(0, 1, 2), column.stream().map(Deal::getPosition).toList());
    }

    @Test
    void move_acrossStages_updatesStageAndRenumbersBothColumns() {
        Pipeline pipeline = newPipeline();
        Stage from = newStage(pipeline, 0);
        Stage to = newStage(pipeline, 1);
        Company company = newCompany();
        Deal d1 = newDeal(pipeline, from, company);
        Deal d2 = newDeal(pipeline, from, company);
        Deal d3 = newDeal(pipeline, to, company);

        dealService.move(d1.getId(), to.getId(), 0);

        List<Deal> target = dealService.getDealsByStageId(to.getId());
        assertEquals(List.of(d1.getId(), d3.getId()), target.stream().map(Deal::getId).toList());
        assertEquals(List.of(0, 1), target.stream().map(Deal::getPosition).toList());

        List<Deal> source = dealService.getDealsByStageId(from.getId());
        assertEquals(List.of(d2.getId()), source.stream().map(Deal::getId).toList());
        assertEquals(0, source.get(0).getPosition());
    }

    @Test
    void move_rejectsStageInAnotherPipeline() {
        Pipeline pipelineA = newPipeline();
        Stage stageA = newStage(pipelineA, 0);
        Pipeline pipelineB = newPipeline();
        Stage stageB = newStage(pipelineB, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipelineA, stageA, company);

        assertThrows(BadRequestException.class,
            () -> dealService.move(deal.getId(), stageB.getId(), 0));
    }

    @Test
    void move_throwsWhenDealMissing() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        assertThrows(ResourceNotFoundException.class, () -> dealService.move(-1, stage.getId(), 0));
    }

    @Test
    void update_changingStage_appendsToNewStageTailWithoutCollision() {
        Pipeline pipeline = newPipeline();
        Stage from = newStage(pipeline, 0);
        Stage to = newStage(pipeline, 1);
        Company company = newCompany();
        Deal a = newDeal(pipeline, to, company);
        Deal b = newDeal(pipeline, to, company);
        Deal moved = newDeal(pipeline, from, company);
        dealService.move(a.getId(), to.getId(), 0);
        dealService.move(b.getId(), to.getId(), 1);

        moved.setStageId(to.getId());
        dealService.update(moved.getId(), moved);

        List<Deal> target = dealService.getDealsByStageId(to.getId());
        assertEquals(List.of(a.getId(), b.getId(), moved.getId()),
            target.stream().map(Deal::getId).toList());
        assertEquals(List.of(0, 1, 2), target.stream().map(Deal::getPosition).toList());
    }
}
