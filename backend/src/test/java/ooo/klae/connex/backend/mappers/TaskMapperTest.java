package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;

class TaskMapperTest extends AbstractMapperTest {

    @Autowired TaskMapper taskMapper;

    private Task build(String description, User assignedTo, Person person, Deal deal) {
        Task task = new Task();
        task.setDescription(description);
        task.setCompleted(false);
        task.setDueDate("2024-12-31 17:00:00");
        task.setAssignedTo(assignedTo);
        task.setPerson(person);
        task.setDeal(deal);
        return task;
    }

    /**
     * Inserts a new task and checks if the generated ID is not zero.
     */
    @Test
    void insert_assignsGeneratedId() {
        Task task = build("Follow up", newUser(), null, null);

        taskMapper.insert(task);

        assertNotEquals(0, task.getId());
    }

    /**
     * Gets a task by ID and checks if the returned task is not null.
     */
    @Test
    void getTaskById_returnsInsertedRow() {
        User user = newUser();
        Company company = newCompany();
        Person person = newPerson(company);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);

        Task task = build("Send proposal", user, person, deal);
        taskMapper.insert(task);

        Task found = taskMapper.getTaskById(task.getId());

        assertNotNull(found);
        assertEquals("Send proposal", found.getDescription());
        assertFalse(found.isCompleted());
        assertNotNull(found.getDueDate());
        assertEquals(user.getId(), found.getAssignedTo().getId());
        assertEquals(person.getId(), found.getPerson().getId());
        assertEquals(deal.getId(), found.getDeal().getId());
    }

    /**
     * Gets a task by ID and checks if the returned task is null when the ID is negative.
     */
    @Test
    void getTaskById_returnsNullWhenMissing() {
        assertNull(taskMapper.getTaskById(-1));
    }

    /**
     * Inserts a new task and checks if the person and deal are null when they are not provided.
     */
    @Test
    void insert_acceptsNullPersonAndDeal() {
        Task task = build("Solo task", newUser(), null, null);

        taskMapper.insert(task);

        Task found = taskMapper.getTaskById(task.getId());
        assertNotNull(found);
        assertTrue(found.getPerson() == null || found.getPerson().getId() == 0);
        assertTrue(found.getDeal() == null || found.getDeal().getId() == 0);
    }

    /**
     * Gets all tasks and checks if the returned list includes the inserted task.
     */
    @Test
    void getAllTasks_includesInsertedRow() {
        Task task = build("In list", newUser(), null, null);
        taskMapper.insert(task);

        List<Task> all = taskMapper.getAllTasks();

        assertTrue(all.stream().anyMatch(x -> x.getId() == task.getId()));
    }

    /**
     * Updates a task and checks if the new values are persisted.
     */
    @Test
    void update_persistsNewValues() {
        Task task = build("Original", newUser(), null, null);
        taskMapper.insert(task);

        task.setDescription("Updated");
        task.setCompleted(true);
        task.setDueDate(null);

        taskMapper.update(task);

        Task found = taskMapper.getTaskById(task.getId());
        assertEquals("Updated", found.getDescription());
        assertTrue(found.isCompleted());
        assertNull(found.getDueDate());
    }

    /**
     * Deletes a task and checks if the task is removed.
     */
    @Test
    void delete_removesRow() {
        Task task = build("Delete me", newUser(), null, null);
        taskMapper.insert(task);

        taskMapper.delete(task.getId());

        assertNull(taskMapper.getTaskById(task.getId()));
    }

    /**
     * Gets tasks by assigned to ID and checks if the returned list includes the inserted task.
     */
    @Test
    void getTasksByAssignedToId_filtersByUser() {
        User user1 = newUser();
        User user2 = newUser();

        Task task1 = build("for u1", user1, null, null);
        Task task2 = build("for u2", user2, null, null);
        taskMapper.insert(task1);
        taskMapper.insert(task2);

        List<Task> matched = taskMapper.getTasksByAssignedToId(user1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == task1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == task2.getId()));
    }

    /**
     * Gets tasks by person ID and checks if the returned list includes the inserted task.
     */
    @Test
    void getTasksByPersonId_filtersByPerson() {
        User user = newUser();
        Person person1 = newPerson(newCompany());
        Person person2 = newPerson(newCompany());

        Task task1 = build("for p1", user, person1, null);
        Task task2 = build("for p2", user, person2, null);
        taskMapper.insert(task1);
        taskMapper.insert(task2);

        List<Task> matched = taskMapper.getTasksByPersonId(person1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == task1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == task2.getId()));
    }

    /**
     * Gets tasks by deal ID and checks if the returned list includes the inserted task.
     */
    @Test
    void getTasksByDealId_filtersByDeal() {
        User user = newUser();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal1 = newDeal(pipeline, stage, newCompany());
        Deal deal2 = newDeal(pipeline, stage, newCompany());

        Task task1 = build("for d1", user, null, deal1);
        Task task2 = build("for d2", user, null, deal2);
        taskMapper.insert(task1);
        taskMapper.insert(task2);

        List<Task> matched = taskMapper.getTasksByDealId(deal1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == task1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == task2.getId()));
    }
}
