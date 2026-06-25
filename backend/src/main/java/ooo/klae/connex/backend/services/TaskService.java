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

    private static final Set<String> AUDIT_FIELDS =
        Set.of("description", "completed", "dueDate");

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
        if (taskMapper.complete(workspaceId, id, currentUser.getId()) == 0) {
            throw new ForbiddenException("Only the task assignee may complete this task");
        }
        Task completed = taskMapper.getTaskById(workspaceId, id);
        auditService.record("task.complete", "task", id, task.getDescription(),
            "Completed task " + task.getDescription(),
            auditService.singleChange("completed", task.isCompleted(), true));
        notificationChanges.publish(workspaceId, "task", id);
        return completed;
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
