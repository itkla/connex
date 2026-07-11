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
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.TaskSummaryDto;

class TaskMapperTest extends AbstractMapperTest {

    @Autowired TaskMapper taskMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    private Task build(String description, User assignedTo, Person person, Deal deal) {
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription(description);
        task.setCompleted(false);
        task.setStatus("todo");
        task.setDueDate("2024-12-31");
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

        Task found = taskMapper.getTaskById(workspace.getId(), task.getId());

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
        assertNull(taskMapper.getTaskById(workspace.getId(), -1));
    }

    /**
     * Inserts a new task and checks if the person and deal are null when they are not provided.
     */
    @Test
    void insert_acceptsNullPersonAndDeal() {
        Task task = build("Solo task", newUser(), null, null);

        taskMapper.insert(task);

        Task found = taskMapper.getTaskById(workspace.getId(), task.getId());
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

        List<Task> all = taskMapper.getAllTasks(workspace.getId());

        assertTrue(all.stream().anyMatch(x -> x.getId() == task.getId()));
    }

    @Test
    void getTasksByPersonIdsBatchesOnlyRequestedWorkspaceContacts() {
        User user = newUser();
        Person included = newPerson(newCompany());
        Person excluded = newPerson(newCompany());
        Task includedTask = build("included", user, included, null);
        Task excludedTask = build("excluded", user, excluded, null);
        taskMapper.insert(includedTask);
        taskMapper.insert(excludedTask);

        List<Task> tasks = taskMapper.getTasksByPersonIds(
            workspace.getId(), List.of(included.getId()));

        assertEquals(List.of(includedTask.getId()), tasks.stream().map(Task::getId).toList());
    }

    @Test
    void getTasksPageLimitsAndCountsWorkspaceRows() {
        Workspace pageWorkspace = newWorkspace();
        User user = newUser();
        Task first = build("first", user, null, null);
        first.setWorkspaceId(pageWorkspace.getId());
        first.setDueDate("2024-01-01");
        taskMapper.insert(first);
        Task second = build("second", user, null, null);
        second.setWorkspaceId(pageWorkspace.getId());
        second.setDueDate("2024-01-01");
        taskMapper.insert(second);
        Task third = build("third", user, null, null);
        third.setWorkspaceId(pageWorkspace.getId());
        third.setDueDate("2024-01-01");
        taskMapper.insert(third);
        Task foreign = build("foreign", user, null, null);
        taskMapper.insert(foreign);

        List<Task> page = taskMapper.getTasksPage(pageWorkspace.getId(), 2, 0);

        assertEquals(List.of(first.getId(), second.getId()), page.stream().map(Task::getId).toList());
        assertEquals(3, taskMapper.countTasks(pageWorkspace.getId()));
        assertTrue(page.stream().noneMatch(task -> task.getId() == foreign.getId()));
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
        task.setStatus("done");
        task.setDueDate(null);

        taskMapper.update(task);

        Task found = taskMapper.getTaskById(workspace.getId(), task.getId());
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

        taskMapper.delete(workspace.getId(), task.getId());

        assertNull(taskMapper.getTaskById(workspace.getId(), task.getId()));
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

        List<Task> matched = taskMapper.getTasksByAssignedToId(workspace.getId(), user1.getId());

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

        List<Task> matched = taskMapper.getTasksByPersonId(workspace.getId(), person1.getId());

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

        List<Task> matched = taskMapper.getTasksByDealId(workspace.getId(), deal1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == task1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == task2.getId()));
    }

    @Test
    void workspaceScopeHidesTasksAndBlocksCompletion() {
        User user = newUser();
        Task task = build("Scoped task", user, null, null);
        taskMapper.insert(task);
        Workspace other = new Workspace();
        other.setName("Other Workspace");
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);

        assertNull(taskMapper.getTaskById(other.getId(), task.getId()));
        assertEquals(0, taskMapper.complete(other.getId(), task.getId(), user.getId(), 0));
        assertFalse(taskMapper.getTaskById(workspace.getId(), task.getId()).isCompleted());
    }

    @Test
    void taskSummaryCountsStatusesAndDueWindowsWithinWorkspace() {
        Workspace target = newWorkspace();
        User user = newUser();
        Task todo = build("todo", user, null, null);
        todo.setWorkspaceId(target.getId());
        taskMapper.insert(todo);
        Task inProgress = build("in-progress", user, null, null);
        inProgress.setWorkspaceId(target.getId());
        inProgress.setStatus("in_progress");
        taskMapper.insert(inProgress);
        Task done = build("done", user, null, null);
        done.setWorkspaceId(target.getId());
        done.setCompleted(true);
        done.setStatus("done");
        taskMapper.insert(done);
        Task farFuture = build("far-future", user, null, null);
        farFuture.setWorkspaceId(target.getId());
        taskMapper.insert(farFuture);
        Task foreign = build("foreign", user, null, null);
        taskMapper.insert(foreign);
        jdbcTemplate.update("UPDATE task SET due_date = DATE_SUB(CURDATE(), INTERVAL 1 DAY) WHERE id = ?", todo.getId());
        jdbcTemplate.update("UPDATE task SET due_date = DATE_ADD(CURDATE(), INTERVAL 7 DAY) WHERE id = ?", inProgress.getId());
        jdbcTemplate.update("UPDATE task SET due_date = CURDATE() WHERE id = ?", done.getId());
        jdbcTemplate.update("UPDATE task SET due_date = DATE_ADD(CURDATE(), INTERVAL 8 DAY) WHERE id = ?", farFuture.getId());
        jdbcTemplate.update("UPDATE task SET due_date = CURDATE() WHERE id = ?", foreign.getId());

        TaskSummaryDto summary = taskMapper.taskSummary(target.getId());

        assertEquals(new TaskSummaryDto(2, 1, 1, 1, 1), summary);
        assertEquals(new TaskSummaryDto(1, 0, 0, 0, 1), taskMapper.taskSummary(workspace.getId()));
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }
}
