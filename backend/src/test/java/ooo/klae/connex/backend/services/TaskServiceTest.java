package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

class TaskServiceTest extends AbstractServiceTest {

    @Autowired TaskService taskService;

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

        taskService.move(t1.getId(), "in_progress", 0);

        assertEquals("in_progress", taskService.getTaskById(t1.getId()).getStatus());
        assertEquals(List.of(t1.getId()), taskMapper.getTaskIdsInStatusOrdered(workspace.getId(), "in_progress"));
        assertEquals(List.of(t2.getId()), taskMapper.getTaskIdsInStatusOrdered(workspace.getId(), "todo"));
        assertEquals(0, taskService.getTaskById(t2.getId()).getPosition());
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
}
