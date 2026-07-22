package ooo.klae.connex.backend.mappers;

import java.time.LocalDate;
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
import ooo.klae.connex.backend.dto.BoardPositionUpdate;
import ooo.klae.connex.backend.dto.MemberScope;
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

    @Test
    void upcomingOpenTasksAreExactBoundedAndExcludeCompletedRows() {
        User user = newUser();
        Task first = build("first", user, null, null);
        first.setDueDate("2026-07-01");
        taskMapper.insert(first);
        Task second = build("second", user, null, null);
        second.setDueDate("2026-07-02");
        taskMapper.insert(second);
        Task third = build("third", user, null, null);
        third.setDueDate("2026-07-03");
        taskMapper.insert(third);
        Task completed = build("completed", user, null, null);
        completed.setDueDate("2026-06-01");
        completed.setCompleted(true);
        completed.setStatus("done");
        taskMapper.insert(completed);

        List<Task> upcoming = taskMapper.getUpcomingOpenTasks(workspace.getId(), 2);

        assertEquals(List.of(first.getId(), second.getId()), upcoming.stream().map(Task::getId).toList());
        assertTrue(upcoming.stream().noneMatch(Task::isCompleted));
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
    void setPositionsUpdatesOnlyExpectedWorkspaceStatus() {
        User user = newUser();
        Task first = build("first", user, null, null);
        Task second = build("second", user, null, null);
        Task unlisted = build("unlisted", user, null, null);
        Task wrongStatus = build("wrong-status", user, null, null);
        taskMapper.insert(first);
        taskMapper.insert(second);
        taskMapper.insert(unlisted);
        taskMapper.insert(wrongStatus);
        taskMapper.moveTask(workspace.getId(), wrongStatus.getId(), "in_progress", false, 9);
        Workspace foreignWorkspace = newWorkspace();
        Task foreign = build("foreign", user, null, null);
        foreign.setWorkspaceId(foreignWorkspace.getId());
        taskMapper.insert(foreign);
        int unlistedPosition = taskMapper.getTaskById(workspace.getId(), unlisted.getId()).getPosition();
        int wrongStatusPosition = taskMapper.getTaskById(workspace.getId(), wrongStatus.getId()).getPosition();
        int foreignPosition = taskMapper.getTaskById(foreignWorkspace.getId(), foreign.getId()).getPosition();

        taskMapper.setPositions(workspace.getId(), "todo", List.of(
            new BoardPositionUpdate(first.getId(), 7),
            new BoardPositionUpdate(second.getId(), 2),
            new BoardPositionUpdate(wrongStatus.getId(), 1),
            new BoardPositionUpdate(foreign.getId(), 0)
        ));

        Task movedFirst = taskMapper.getTaskById(workspace.getId(), first.getId());
        Task movedSecond = taskMapper.getTaskById(workspace.getId(), second.getId());
        assertEquals(7, movedFirst.getPosition());
        assertEquals(2, movedSecond.getPosition());
        assertEquals("todo", movedFirst.getStatus());
        assertFalse(movedFirst.isCompleted());
        assertEquals("todo", movedSecond.getStatus());
        assertFalse(movedSecond.isCompleted());
        assertEquals(unlistedPosition,
            taskMapper.getTaskById(workspace.getId(), unlisted.getId()).getPosition());
        assertEquals(wrongStatusPosition,
            taskMapper.getTaskById(workspace.getId(), wrongStatus.getId()).getPosition());
        assertEquals(foreignPosition,
            taskMapper.getTaskById(foreignWorkspace.getId(), foreign.getId()).getPosition());
    }

    @Test
    void taskSummaryCountsStatusesAndDueWindowsWithinWorkspace() {
        LocalDate today = LocalDate.of(2026, 7, 10);
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
        Workspace foreignWorkspace = newWorkspace();
        Task foreign = build("foreign", user, null, null);
        foreign.setWorkspaceId(foreignWorkspace.getId());
        taskMapper.insert(foreign);
        jdbcTemplate.update("UPDATE task SET due_date = ? WHERE id = ?", today.minusDays(1), todo.getId());
        jdbcTemplate.update("UPDATE task SET due_date = ? WHERE id = ?", today.plusDays(7), inProgress.getId());
        jdbcTemplate.update("UPDATE task SET due_date = ? WHERE id = ?", today, done.getId());
        jdbcTemplate.update("UPDATE task SET due_date = ? WHERE id = ?", today.plusDays(8), farFuture.getId());
        jdbcTemplate.update("UPDATE task SET due_date = ? WHERE id = ?", today, foreign.getId());

        TaskSummaryDto summary = taskMapper.taskSummary(target.getId(), today, MemberScope.allTeam());

        assertEquals(new TaskSummaryDto(2, 1, 1, 1, 1), summary);
        assertEquals(new TaskSummaryDto(1, 0, 0, 0, 1),
            taskMapper.taskSummary(foreignWorkspace.getId(), today, MemberScope.allTeam()));
    }

    @Test
    void taskSummaryHonorsMemberScopeByAssignee() {
        LocalDate today = LocalDate.of(2026, 7, 10);
        Workspace target = newWorkspace();
        User assignee = newUser();
        User other = newUser();
        Task mine = build("mine", assignee, null, null);
        mine.setWorkspaceId(target.getId());
        taskMapper.insert(mine);
        Task theirs = build("theirs", other, null, null);
        theirs.setWorkspaceId(target.getId());
        taskMapper.insert(theirs);
        Task nobody = build("nobody", assignee, null, null);
        nobody.setWorkspaceId(target.getId());
        taskMapper.insert(nobody);
        jdbcTemplate.update("UPDATE task SET assigned_to_id = NULL WHERE id = ?", nobody.getId());

        MemberScope me = MemberScope.fromRequest("me", null, assignee.getId());
        MemberScope members = MemberScope.fromRequest(
            "members", List.of(assignee.getId(), other.getId()), assignee.getId());
        MemberScope unassigned = MemberScope.fromRequest("unassigned", null, assignee.getId());

        assertEquals(3, taskMapper.taskSummary(target.getId(), today, MemberScope.allTeam()).todo());
        assertEquals(1, taskMapper.taskSummary(target.getId(), today, me).todo());
        assertEquals(2, taskMapper.taskSummary(target.getId(), today, members).todo());
        assertEquals(1, taskMapper.taskSummary(target.getId(), today, unassigned).todo());
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }
}
