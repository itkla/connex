package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Set;

import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for {@code Task} operations.
 * Handles mapping between {@code TaskDto} and {@code Task} bean.
 * Delegates persistence to {@code TaskMapper}.
 */

@Service
@RequiredArgsConstructor
public class TaskService {
    private final TaskMapper taskMapper;
    private final DealMapper dealMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final NotificationChangePublisher notificationChanges;

    private static final String STATUS_TODO = "todo";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_DONE = "done";
    private static final Set<String> VALID_STATUSES = Set.of(STATUS_TODO, STATUS_IN_PROGRESS, STATUS_DONE);

    private static final Set<String> AUDIT_FIELDS =
        Set.of("description", "completed", "status", "dueDate");

    public List<Task> getAllTasks() {
        return taskMapper.getAllTasks(workspaceService.getCurrentWorkspaceId());
    }

    public List<Task> getTasksByAssignedToId(int assignedToId) {
        return taskMapper.getTasksByAssignedToId(workspaceService.getCurrentWorkspaceId(), assignedToId);
    }

    public List<Task> getTasksByPersonId(int personId) {
        return taskMapper.getTasksByPersonId(workspaceService.getCurrentWorkspaceId(), personId);
    }

    public List<Task> getTasksByDealId(int dealId) {
        return taskMapper.getTasksByDealId(workspaceService.getCurrentWorkspaceId(), dealId);
    }

    public Task getTaskById(int id) {
        Task task = taskMapper.getTaskById(workspaceService.getCurrentWorkspaceId(), id);
        if (task == null) throw new ResourceNotFoundException("Task not found with id: " + id);
        return task;
    }

    @RequirePermission(Permission.TASK_CREATE)
    public Task create(Task task) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        task.setWorkspaceId(workspaceId);
        validateReferences(task, workspaceId);
        task.setStatus(task.isCompleted() ? STATUS_DONE : STATUS_TODO);
        task.setPosition(taskMapper.nextTaskPosition(workspaceId, task.getStatus()));
        taskMapper.insert(task);
        auditService.record("task.create", "task", task.getId(), task.getDescription(),
            "Created task " + task.getDescription(),
            auditService.diff(null, task, AUDIT_FIELDS));
        notificationChanges.publish(workspaceId, "task", task.getId());
        return task;
    }

    @RequirePermission(Permission.TASK_UPDATE)
    public Task update(int id, Task task) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Task before = taskMapper.getTaskById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Task not found with id: " + id);
        task.setId(id);
        task.setWorkspaceId(workspaceId);
        validateReferences(task, workspaceId);
        String beforeStatus = before.getStatus() != null ? before.getStatus() : STATUS_TODO;
        String resolved = task.isCompleted() ? STATUS_DONE
            : (STATUS_DONE.equals(beforeStatus) ? STATUS_TODO : beforeStatus);
        task.setCompleted(STATUS_DONE.equals(resolved));
        task.setStatus(resolved);
        task.setPosition(resolved.equals(beforeStatus)
            ? before.getPosition()
            : taskMapper.nextTaskPosition(workspaceId, resolved));
        taskMapper.update(task);
        auditService.record("task.update", "task", id, task.getDescription(),
            "Updated task " + task.getDescription(),
            auditService.diff(before, task, AUDIT_FIELDS));
        notificationChanges.publish(workspaceId, "task", id);
        return task;
    }

    @RequirePermission(Permission.TASK_DELETE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Task before = taskMapper.getTaskById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Task not found with id: " + id);
        taskMapper.delete(workspaceId, id);
        auditService.record("task.delete", "task", id, before.getDescription(),
            "Deleted task " + before.getDescription(),
            auditService.diff(before, null, AUDIT_FIELDS));
        notificationChanges.publish(workspaceId, "task", id);
    }

    @Transactional
    @RequirePermission(Permission.TASK_UPDATE)
    public Task complete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        User currentUser = authService.getCurrentUser();
        Task task = taskMapper.getTaskById(workspaceId, id);
        if (task == null) throw new ResourceNotFoundException("Task not found with id: " + id);
        if (task.getAssignedTo() == null || task.getAssignedTo().getId() != currentUser.getId()) {
            throw new ForbiddenException("Only the task assignee may complete this task");
        }
        if (task.isCompleted()) {
            return task;
        }
        int donePosition = taskMapper.nextTaskPosition(workspaceId, STATUS_DONE);
        if (taskMapper.complete(workspaceId, id, currentUser.getId(), donePosition) == 0) {
            throw new ForbiddenException("Only the task assignee may complete this task");
        }
        Task completed = taskMapper.getTaskById(workspaceId, id);
        auditService.record("task.complete", "task", id, task.getDescription(),
            "Completed task " + task.getDescription(),
            auditService.singleChange("completed", task.isCompleted(), true));
        notificationChanges.publish(workspaceId, "task", id);
        return completed;
    }

    /**
     * Moves a task to a target status column and ordinal position on the Kanban board, renumbering
     * the affected column(s) so positions stay contiguous and 0-based. Dragging a task into the
     * {@code done} column completes it, so it is gated by the same assignee-only rule as
     * {@link #complete(int)}; the {@code completed} flag is kept in lockstep with {@code status}.
     * @param id the task to move
     * @param status the target status: {@code todo}, {@code in_progress} or {@code done}
     * @param position the desired 0-based index within the target column (clamped to the column size)
     * @return the moved task
     */
    @Transactional
    @RequirePermission(Permission.TASK_UPDATE)
    public Task move(int id, String status, int position) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (status == null || !VALID_STATUSES.contains(status)) {
            throw new BadRequestException("Invalid task status: " + status);
        }
        Task before = taskMapper.getTaskById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Task not found with id: " + id);
        String oldStatus = before.getStatus() != null ? before.getStatus() : STATUS_TODO;
        boolean toDone = STATUS_DONE.equals(status);
        boolean fromDone = STATUS_DONE.equals(oldStatus);
        if (toDone != fromDone) {
            User currentUser = authService.getCurrentUser();
            if (before.getAssignedTo() == null || before.getAssignedTo().getId() != currentUser.getId()) {
                throw new ForbiddenException("Only the task assignee may change this task's completion");
            }
        }
        boolean statusChanged = !status.equals(oldStatus);

        List<Integer> target = taskMapper.getTaskIdsInStatusOrdered(workspaceId, status);
        target.removeIf(existing -> existing == id);
        int index = Math.max(0, Math.min(position, target.size()));
        target.add(index, id);

        if (statusChanged) {
            List<Integer> source = taskMapper.getTaskIdsInStatusOrdered(workspaceId, oldStatus);
            source.removeIf(existing -> existing == id);
            for (int i = 0; i < source.size(); i++) {
                taskMapper.setPosition(workspaceId, source.get(i), i);
            }
        }

        taskMapper.moveTask(workspaceId, id, status, toDone, index);
        for (int i = 0; i < target.size(); i++) {
            int tid = target.get(i);
            if (tid != id) taskMapper.setPosition(workspaceId, tid, i);
        }

        Task moved = taskMapper.getTaskById(workspaceId, id);
        auditService.record("task.update", "task", id, before.getDescription(),
            statusChanged ? "Moved task " + before.getDescription() + " to " + status
                          : "Reordered task " + before.getDescription(),
            auditService.diff(before, moved, AUDIT_FIELDS));
        notificationChanges.publish(workspaceId, "task", id);
        return moved;
    }

    /**
     * Changes only a task's due date, leaving description, assignee, status, position and links
     * untouched. Unlike {@link #update(int, Task)} this cannot clobber other fields from a stale
     * client payload — it writes a single column after confirming the task belongs to the caller's
     * workspace.
     * @param id the task to reschedule
     * @param dueDate the target due date as a {@code YYYY-MM-DD} calendar day
     * @return the rescheduled task
     */
    @RequirePermission(Permission.TASK_UPDATE)
    public Task reschedule(int id, String dueDate) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Task before = taskMapper.getTaskById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Task not found with id: " + id);
        taskMapper.updateDueDate(workspaceId, id, dueDate);
        Task after = taskMapper.getTaskById(workspaceId, id);
        auditService.record("task.update", "task", id, after.getDescription(),
            "Rescheduled task " + after.getDescription(),
            auditService.singleChange("dueDate", before.getDueDate(), dueDate));
        notificationChanges.publish(workspaceId, "task", id);
        return after;
    }

    private void validateReferences(Task task, int workspaceId) {
        if (task.getAssignedTo() == null || task.getAssignedTo().getId() == 0) {
            throw new BadRequestException("Task assignee is required");
        }
        workspaceService.requireMember(workspaceId, task.getAssignedTo().getId());
        if (task.getDeal() != null && !dealMapper.exists(workspaceId, task.getDeal().getId())) {
            throw new BadRequestException("Task deal must belong to the current workspace");
        }
    }
}
