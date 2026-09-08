package ooo.klae.connex.backend.services;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.TaskSummaryDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

@Transactional(isolation = Isolation.READ_COMMITTED)
class TaskServiceTest extends AbstractServiceTest {

    @Autowired TaskService taskService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void directCompanyLinkCanBeCreatedUpdatedReadAndCleared() {
        Company first = newCompany();
        Company second = newCompany();
        Task create = new Task();
        create.setDescription("Company task");
        create.setAssignedTo(currentUser);
        create.setCompany(first);

        Task created = taskService.create(create);
        assertEquals(first.getId(), created.getCompany().getId());
        assertEquals(List.of(created.getId()), taskService.getTasksByCompanyId(first.getId())
            .stream().map(Task::getId).toList());

        Task update = updateDraft(created, "Moved company task");
        update.setCompany(second);
        Task moved = taskService.update(created.getId(), update);
        assertEquals(second.getId(), moved.getCompany().getId());
        assertTrue(taskService.getTasksByCompanyId(first.getId()).isEmpty());

        Task clear = updateDraft(moved, "Cleared company task");
        clear.setCompany(null);
        Task cleared = taskService.update(created.getId(), clear);
        assertTrue(cleared.getCompany() == null || cleared.getCompany().getId() == 0);
    }

    @Test
    void companyFilterIncludesDirectAndDerivedLinksOnce() {
        Company company = newCompany();
        var person = newPerson(company);
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 0);
        var deal = newDeal(pipeline, stage, company);
        Task task = newTask(currentUser, person, deal);
        Task update = updateDraft(task, task.getDescription());
        update.setCompany(company);
        taskService.update(task.getId(), update);

        assertEquals(List.of(task.getId()), taskService.getTasksByCompanyId(company.getId())
            .stream().map(Task::getId).toList());
    }

    @Test
    void foreignAndArchivedCompaniesCannotBeLinkedOrFiltered() {
        Company active = newCompany();
        Workspace foreignWorkspace = new Workspace();
        foreignWorkspace.setName("Foreign " + unique());
        foreignWorkspace.setSlug("foreign-company-" + unique());
        workspaceMapper.insert(foreignWorkspace);
        Company foreign = new Company();
        foreign.setWorkspaceId(foreignWorkspace.getId());
        foreign.setName("Foreign company " + unique());
        companyMapper.insert(foreign);
        Task foreignCreate = new Task();
        foreignCreate.setDescription("Foreign company task");
        foreignCreate.setAssignedTo(currentUser);
        foreignCreate.setCompany(foreign);

        assertThrows(BadRequestException.class, () -> taskService.create(foreignCreate));
        assertThrows(ResourceNotFoundException.class,
            () -> taskService.getTasksByCompanyId(foreign.getId()));

        Task task = newTask(currentUser, null, null);
        Task foreignUpdate = updateDraft(task, "Foreign company update");
        foreignUpdate.setCompany(foreign);
        assertThrows(BadRequestException.class,
            () -> taskService.update(task.getId(), foreignUpdate));
        assertTrue(taskService.getTaskById(task.getId()).getCompany() == null);

        Task retained = new Task();
        retained.setDescription("Retained archived company task");
        retained.setAssignedTo(currentUser);
        retained.setCompany(active);
        retained = taskService.create(retained);
        assertEquals(1, companyMapper.archive(workspace.getId(), active.getId()));
        assertEquals(active.getId(), taskService.getTaskById(retained.getId()).getCompany().getId());
        Task archivedCreate = new Task();
        archivedCreate.setDescription("Archived company task");
        archivedCreate.setAssignedTo(currentUser);
        archivedCreate.setCompany(active);
        assertThrows(BadRequestException.class, () -> taskService.create(archivedCreate));
        assertThrows(ResourceNotFoundException.class,
            () -> taskService.getTasksByCompanyId(active.getId()));
    }

    @Test
    void conditionalDeleteRefusesChangedStateAndDeletesMatchingState() {
        Task task = newTask(currentUser, null, null);

        assertThrows(
            ConflictException.class,
            () -> taskService.deleteIf(task.getId(), current -> false));
        assertEquals(task.getId(), taskService.getTaskById(task.getId()).getId());

        taskService.deleteIf(
            task.getId(),
            current -> task.getDescription().equals(current.getDescription()));

        assertThrows(ResourceNotFoundException.class, () -> taskService.getTaskById(task.getId()));
    }

    @Test
    void move_reordersWithinStatusContiguously() {
        Task t1 = newTask(currentUser, null, null);
        Task t2 = newTask(currentUser, null, null);
        Task t3 = newTask(currentUser, null, null);

        taskService.move(t3.getId(), "todo", 0);

        assertEquals(List.of(t3.getId(), t1.getId(), t2.getId()),
            taskMapper.getTaskIdsInStatusOrdered(workspace.getId(), "todo"));
        assertEquals(0, taskService.getTaskById(t3.getId()).getPosition());
        assertEquals(2, taskService.getTaskById(t2.getId()).getPosition());
    }

    @Test
    void move_acrossStatuses_updatesStatusAndRenumbersBothColumns() {
        Task t1 = newTask(currentUser, null, null);
        Task t2 = newTask(currentUser, null, null);
        Task t3 = newTask(currentUser, null, null);
        Task t4 = newTask(currentUser, null, null);
        Task t5 = newTask(currentUser, null, null);
        taskMapper.moveTask(workspace.getId(), t4.getId(), "in_progress", false, 0);
        taskMapper.moveTask(workspace.getId(), t5.getId(), "in_progress", false, 1);

        taskService.move(t1.getId(), "in_progress", 1);

        assertEquals("in_progress", taskService.getTaskById(t1.getId()).getStatus());
        assertEquals(List.of(t4.getId(), t1.getId(), t5.getId()),
            taskMapper.getTaskIdsInStatusOrdered(workspace.getId(), "in_progress"));
        assertEquals(List.of(t2.getId(), t3.getId()),
            taskMapper.getTaskIdsInStatusOrdered(workspace.getId(), "todo"));
        assertEquals(0, taskService.getTaskById(t2.getId()).getPosition());
        assertEquals(1, taskService.getTaskById(t3.getId()).getPosition());
    }

    @Test
    void move_onlySourceTaskIntoEmptyStatusSkipsEmptySourceBatch() {
        Task task = newTask(currentUser, null, null);

        Task moved = taskService.move(task.getId(), "in_progress", 0);

        assertEquals("in_progress", moved.getStatus());
        assertEquals(0, moved.getPosition());
        assertTrue(taskMapper.getTaskIdsInStatusOrdered(workspace.getId(), "todo").isEmpty());
    }

    @Test
    void move_toDone_completesTaskForAssignee() {
        Task task = newTask(currentUser, null, null);

        taskService.move(task.getId(), "done", 0);

        Task moved = taskService.getTaskById(task.getId());
        assertEquals("done", moved.getStatus());
        assertTrue(moved.isCompleted());
    }

    @Test
    void move_outOfDone_clearsCompleted() {
        Task task = newTask(currentUser, null, null);
        taskService.move(task.getId(), "done", 0);

        taskService.move(task.getId(), "todo", 0);

        Task moved = taskService.getTaskById(task.getId());
        assertEquals("todo", moved.getStatus());
        assertFalse(moved.isCompleted());
    }

    @Test
    void complete_compactsSourceAndAppendsToDone() {
        Task first = newTask(currentUser, null, null);
        Task completing = newTask(currentUser, null, null);
        Task last = newTask(currentUser, null, null);

        taskService.complete(completing.getId());

        assertEquals(
            List.of(first.getId(), last.getId()),
            taskMapper.getTaskIdsInStatusOrdered(workspace.getId(), "todo")
        );
        assertEquals(0, taskService.getTaskById(first.getId()).getPosition());
        assertEquals(1, taskService.getTaskById(last.getId()).getPosition());
        assertEquals(0, taskService.getTaskById(completing.getId()).getPosition());
    }

    @Test
    void versionAwareCompleteUsesTheProjectedCanonicalState() {
        Task task = newTask(currentUser, null, null);
        String version = taskService.findOpenAssignedWork(
            Instant.parse("2026-08-30T12:00:00Z"), 10).items().stream()
            .filter(item -> item.task().getId() == task.getId())
            .findFirst().orElseThrow().currentVersion();

        Task completed = taskService.complete(task.getId(), version);

        assertTrue(completed.isCompleted());
        assertEquals("done", completed.getStatus());
    }

    @Test
    void assignedWorkVersionChangesWhenDirectCompanyLinkChanges() {
        Company first = newCompany();
        Company second = newCompany();
        Task task = newTask(currentUser, null, null);
        jdbcTemplate.update(
            "UPDATE task SET company_id = ?, updated_at = '2026-08-30 12:00:00' WHERE id = ?",
            first.getId(), task.getId());
        String before = taskService.findOpenAssignedWork(
            Instant.parse("2026-08-30T12:00:00Z"), 10).items().stream()
            .filter(item -> item.task().getId() == task.getId())
            .findFirst().orElseThrow().currentVersion();

        jdbcTemplate.update(
            "UPDATE task SET company_id = ?, updated_at = '2026-08-30 12:00:00' WHERE id = ?",
            second.getId(), task.getId());
        String after = taskService.findOpenAssignedWork(
            Instant.parse("2026-08-30T12:00:00Z"), 10).items().stream()
            .filter(item -> item.task().getId() == task.getId())
            .findFirst().orElseThrow().currentVersion();

        assertFalse(before.equals(after));
    }

    @Test
    void versionAwareCompleteRejectsAConcurrentSourceChange() {
        Task task = newTask(currentUser, null, null);
        String version = taskService.findOpenAssignedWork(
            Instant.parse("2026-08-30T12:00:00Z"), 10).items().stream()
            .filter(item -> item.task().getId() == task.getId())
            .findFirst().orElseThrow().currentVersion();
        jdbcTemplate.update(
            "UPDATE task SET description = ?, updated_at = CURRENT_TIMESTAMP(6) "
                + "WHERE workspace_id = ? AND id = ?",
            "changed", workspace.getId(), task.getId());

        assertThrows(ConflictException.class, () -> taskService.complete(task.getId(), version));
        assertFalse(taskService.getTaskById(task.getId()).isCompleted());
    }

    @Test
    void versionAwareCompleteConcealsAnotherAssigneesTask() {
        User other = newUser();
        Task task = newTask(other, null, null);

        assertThrows(
            ResourceNotFoundException.class,
            () -> taskService.complete(task.getId(), "a".repeat(64)));
    }

    @Test
    void update_completionTransitionCompactsSourceAndAppendsToDone() {
        Task first = newTask(currentUser, null, null);
        Task completing = newTask(currentUser, null, null);
        Task last = newTask(currentUser, null, null);
        Task update = updateDraft(completing, "Complete through update");
        update.setCompleted(true);

        taskService.update(completing.getId(), update);

        assertEquals(
            List.of(first.getId(), last.getId()),
            taskMapper.getTaskIdsInStatusOrdered(workspace.getId(), "todo")
        );
        assertEquals(1, taskService.getTaskById(last.getId()).getPosition());
        assertEquals(0, taskService.getTaskById(completing.getId()).getPosition());
    }

    @Test
    void update_reopenTransitionCompactsDoneAndAppendsToTodo() {
        Task todo = newTask(currentUser, null, null);
        Task reopening = newTask(currentUser, null, null);
        Task doneSurvivor = newTask(currentUser, null, null);
        taskService.move(reopening.getId(), "done", 0);
        taskService.move(doneSurvivor.getId(), "done", 1);
        Task persisted = taskService.getTaskById(reopening.getId());
        Task update = updateDraft(persisted, "Reopen through update");
        update.setCompleted(false);

        taskService.update(reopening.getId(), update);

        assertEquals(
            List.of(todo.getId(), reopening.getId()),
            taskMapper.getTaskIdsInStatusOrdered(workspace.getId(), "todo")
        );
        assertEquals(
            List.of(doneSurvivor.getId()),
            taskMapper.getTaskIdsInStatusOrdered(workspace.getId(), "done")
        );
        assertEquals(0, taskService.getTaskById(doneSurvivor.getId()).getPosition());
        assertEquals(1, taskService.getTaskById(reopening.getId()).getPosition());
    }

    @Test
    void completionEvidenceIsAppendOnlyForEachFalseToTrueTransition() {
        Task task = newTask(currentUser, null, null);

        taskService.move(task.getId(), "done", 0);
        assertEquals(1, completionEventCount(task.getId()));

        taskService.move(task.getId(), "todo", 0);
        assertEquals(1, completionEventCount(task.getId()));

        Task update = updateDraft(taskService.getTaskById(task.getId()), "Complete again");
        update.setCompleted(true);
        taskService.update(task.getId(), update);
        assertEquals(2, completionEventCount(task.getId()));

        taskService.complete(task.getId());
        assertEquals(2, completionEventCount(task.getId()));

        Task createdCompleted = new Task();
        createdCompleted.setDescription("Created completed");
        createdCompleted.setAssignedTo(currentUser);
        createdCompleted.setCompleted(true);
        createdCompleted = taskService.create(createdCompleted);
        assertEquals(0, completionEventCount(createdCompleted.getId()));
    }

    @Test
    void delete_compactsSourceColumn() {
        Task first = newTask(currentUser, null, null);
        Task deleting = newTask(currentUser, null, null);
        Task last = newTask(currentUser, null, null);

        taskService.delete(deleting.getId());

        assertEquals(
            List.of(first.getId(), last.getId()),
            taskMapper.getTaskIdsInStatusOrdered(workspace.getId(), "todo")
        );
        assertEquals(0, taskService.getTaskById(first.getId()).getPosition());
        assertEquals(1, taskService.getTaskById(last.getId()).getPosition());
    }

    @Test
    void move_toDone_byNonAssignee_throwsForbidden() {
        User assignee = newUser();
        Task task = newTask(assignee, null, null);

        assertThrows(ForbiddenException.class, () -> taskService.move(task.getId(), "done", 0));
    }

    @Test
    void move_rejectsInvalidStatus() {
        Task task = newTask(currentUser, null, null);

        assertThrows(BadRequestException.class, () -> taskService.move(task.getId(), "archived", 0));
    }

    @Test
    void move_throwsWhenTaskMissing() {
        assertThrows(ResourceNotFoundException.class, () -> taskService.move(-1, "todo", 0));
    }

    @Test
    void move_outOfDone_byNonAssignee_throwsForbidden() {
        User assignee = newUser();
        Task task = new Task();
        task.setWorkspaceId(workspace.getId());
        task.setDescription("done_" + unique());
        task.setCompleted(true);
        task.setStatus("done");
        task.setAssignedTo(assignee);
        taskMapper.insert(task);

        assertThrows(ForbiddenException.class, () -> taskService.move(task.getId(), "todo", 0));
    }

    @Test
    void update_completionByNonAssignee_throwsForbidden() {
        User assignee = newUser();
        Task task = newTask(assignee, null, null);
        Task update = updateDraft(task, "Cannot complete");
        update.setCompleted(true);

        assertThrows(ForbiddenException.class, () -> taskService.update(task.getId(), update));

        assertFalse(taskService.getTaskById(task.getId()).isCompleted());
    }

    @Test
    void update_reopenByNonAssignee_throwsForbidden() {
        User assignee = newUser();
        Task task = newTask(assignee, null, null);
        taskMapper.moveTask(workspace.getId(), task.getId(), "done", true, 0);
        Task persisted = taskService.getTaskById(task.getId());
        Task update = updateDraft(persisted, "Cannot reopen");
        update.setCompleted(false);

        assertThrows(ForbiddenException.class, () -> taskService.update(task.getId(), update));

        assertTrue(taskService.getTaskById(task.getId()).isCompleted());
    }

    @Test
    void update_nonCompletionFieldsByNonAssignee_succeeds() {
        User assignee = newUser();
        Task task = newTask(assignee, null, null);
        Task update = updateDraft(task, "Allowed edit");

        Task updated = taskService.update(task.getId(), update);

        assertEquals("Allowed edit", updated.getDescription());
        assertFalse(updated.isCompleted());
        assertEquals(assignee.getId(), updated.getAssignedTo().getId());
    }

    @Test
    void reschedule_updatesOnlyDueDate() {
        Task task = newTask(currentUser, null, null);
        String originalDescription = taskService.getTaskById(task.getId()).getDescription();

        taskService.reschedule(task.getId(), "2025-03-15");

        Task after = taskService.getTaskById(task.getId());
        assertEquals("2025-03-15", after.getDueDate());
        assertEquals(originalDescription, after.getDescription());
        assertEquals("todo", after.getStatus());
        assertFalse(after.isCompleted());
        assertEquals(currentUser.getId(), after.getAssignedTo().getId());
    }

    @Test
    void reschedule_throwsWhenTaskMissing() {
        assertThrows(ResourceNotFoundException.class, () -> taskService.reschedule(-1, "2025-03-15"));
    }

    @Test
    void reschedule_rejectsInvalidDate() {
        Task task = newTask(currentUser, null, null);
        assertThrows(BadRequestException.class, () -> taskService.reschedule(task.getId(), "2025-13-45"));
    }

    @Test
    void taskSummaryUsesTheActiveWorkspace() {
        Task todo = newTask(currentUser, null, null);
        Task inProgress = newTask(currentUser, null, null);
        taskMapper.moveTask(workspace.getId(), inProgress.getId(), "in_progress", false, 0);
        Task done = newTask(currentUser, null, null);
        taskMapper.moveTask(workspace.getId(), done.getId(), "done", true, 0);
        jdbcTemplate.update("UPDATE task SET due_date = DATE_SUB(CURDATE(), INTERVAL 1 DAY) WHERE id = ?", todo.getId());
        jdbcTemplate.update("UPDATE task SET due_date = DATE_ADD(CURDATE(), INTERVAL 7 DAY) WHERE id = ?", inProgress.getId());
        jdbcTemplate.update("UPDATE task SET due_date = CURDATE() WHERE id = ?", done.getId());

        Workspace foreignWorkspace = new Workspace();
        foreignWorkspace.setName("Foreign " + unique());
        foreignWorkspace.setSlug("foreign_" + unique());
        workspaceMapper.insert(foreignWorkspace);
        Task foreign = new Task();
        foreign.setWorkspaceId(foreignWorkspace.getId());
        foreign.setDescription("foreign");
        foreign.setCompleted(false);
        foreign.setStatus("todo");
        foreign.setAssignedTo(currentUser);
        taskMapper.insert(foreign);
        jdbcTemplate.update("UPDATE task SET due_date = CURDATE() WHERE id = ?", foreign.getId());

        assertEquals(new TaskSummaryDto(1, 1, 1, 1, 1), taskService.getTaskSummary(MemberScope.allTeam()));
    }

    private Task updateDraft(Task task, String description) {
        Task update = new Task();
        update.setDescription(description);
        update.setCompleted(task.isCompleted());
        update.setDueDate(task.getDueDate());
        update.setAssignedTo(task.getAssignedTo());
        update.setPerson(task.getPerson());
        update.setDeal(task.getDeal());
        update.setCompany(task.getCompany());
        return update;
    }

    private int completionEventCount(int taskId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM task_completion_event WHERE workspace_id = ? AND task_id = ?",
            Integer.class,
            workspace.getId(),
            taskId);
    }
}
