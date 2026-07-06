package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealStageHistory;
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

    @Test
    void create_recordsInitialStageHistory() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();

        Deal deal = new Deal();
        deal.setName("Deal " + unique());
        deal.setWorkspaceId(workspace.getId());
        deal.setValue(1000.0);
        deal.setCurrency("JPY");
        deal.setPipelineId(pipeline.getId());
        deal.setStageId(stage.getId());
        deal.setCompanyId(company.getId());
        Deal created = dealService.create(deal);

        List<DealStageHistory> history = dealService.getStageHistory(created.getId());
        assertEquals(1, history.size());
        assertEquals(stage.getId(), history.get(0).getStageId());
    }

    @Test
    void move_acrossStages_recordsStageHistory() {
        Pipeline pipeline = newPipeline();
        Stage from = newStage(pipeline, 0);
        Stage to = newStage(pipeline, 1);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, from, company);

        dealService.move(deal.getId(), to.getId(), 0);

        List<DealStageHistory> history = dealService.getStageHistory(deal.getId());
        assertEquals(1, history.size());
        assertEquals(to.getId(), history.get(0).getStageId());
    }

    @Test
    void move_withinSameStage_doesNotRecordStageHistory() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal a = newDeal(pipeline, stage, company);
        Deal b = newDeal(pipeline, stage, company);

        dealService.move(b.getId(), stage.getId(), 0);

        assertTrue(dealService.getStageHistory(b.getId()).isEmpty());
    }

    @Test
    void update_changingStage_recordsStageHistory() {
        Pipeline pipeline = newPipeline();
        Stage from = newStage(pipeline, 0);
        Stage to = newStage(pipeline, 1);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, from, company);

        deal.setStageId(to.getId());
        dealService.update(deal.getId(), deal);

        List<DealStageHistory> history = dealService.getStageHistory(deal.getId());
        assertEquals(1, history.size());
        assertEquals(to.getId(), history.get(0).getStageId());
    }

    @Test
    void reopen_fromTerminalStage_recordsReturnStageHistory() {
        Pipeline pipeline = newPipeline();
        Stage open = newStage(pipeline, 0);
        Stage won = new Stage();
        won.setName("Won " + unique());
        won.setPipeline(pipeline);
        won.setPosition(1);
        won.setWorkspaceId(workspace.getId());
        won.setSuccess(true);
        pipelineMapper.insertStage(won);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, open, company);

        dealService.move(deal.getId(), won.getId(), 0);
        dealService.reopen(deal.getId());

        List<DealStageHistory> history = dealService.getStageHistory(deal.getId());
        assertEquals(2, history.size());
        assertEquals(won.getId(), history.get(0).getStageId());
        assertEquals(open.getId(), history.get(1).getStageId());
        assertEquals(open.getId(), dealService.getDealById(deal.getId()).getStageId());
    }

    @Test
    void getStageHistory_throwsWhenDealMissing() {
        assertThrows(ResourceNotFoundException.class, () -> dealService.getStageHistory(-1));
    }

    @Test
    void reschedule_updatesOnlyExpectedCloseDate() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        String name = deal.getName();

        dealService.reschedule(deal.getId(), "2025-06-30");

        Deal after = dealService.getDealById(deal.getId());
        assertEquals("2025-06-30", after.getExpectedCloseDate());
        assertEquals(name, after.getName());
        assertEquals(1000.0, after.getValue(), 0.0001);
        assertEquals(stage.getId(), after.getStageId());
        assertEquals(pipeline.getId(), after.getPipelineId());
    }

    @Test
    void reschedule_doesNotReopenClosedDeal() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        dealService.close(deal.getId(), Boolean.TRUE, "signed", 1500.0);

        dealService.reschedule(deal.getId(), "2025-06-30");

        Deal after = dealService.getDealById(deal.getId());
        assertEquals("2025-06-30", after.getExpectedCloseDate());
        assertEquals(Boolean.TRUE, after.getWon());
        assertNotNull(after.getClosedAt());
    }

    @Test
    void reschedule_throwsWhenDealMissing() {
        assertThrows(ResourceNotFoundException.class, () -> dealService.reschedule(-1, "2025-06-30"));
    }

    @Test
    void reschedule_rejectsInvalidDate() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        assertThrows(BadRequestException.class, () -> dealService.reschedule(deal.getId(), "9999-99-99"));
    }
}
