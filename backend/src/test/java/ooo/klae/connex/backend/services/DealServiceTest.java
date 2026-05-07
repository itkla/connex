package ooo.klae.connex.backend.services;

import java.util.List;

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
}
