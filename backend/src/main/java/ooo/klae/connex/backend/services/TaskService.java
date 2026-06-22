package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Set;

import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

import org.springframework.stereotype.Service;

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
    private final AuditService auditService;

    private static final Set<String> AUDIT_FIELDS =
        Set.of("description", "completed", "dueDate");

    public List<Task> getAllTasks() {
        return taskMapper.getAllTasks();
    }

    public List<Task> getTasksByAssignedToId(int assignedToId) {
        return taskMapper.getTasksByAssignedToId(assignedToId);
    }

    public List<Task> getTasksByPersonId(int personId) {
        return taskMapper.getTasksByPersonId(personId);
    }

    public List<Task> getTasksByDealId(int dealId) {
        return taskMapper.getTasksByDealId(dealId);
    }

    public Task getTaskById(int id) {
        Task task = taskMapper.getTaskById(id);
        if (task == null) throw new ResourceNotFoundException("Task not found with id: " + id);
        return task;
    }

    public Task create(Task task) {
        taskMapper.insert(task);
        auditService.record("task.create", "task", task.getId(), task.getDescription(),
            "Created task " + task.getDescription(),
            auditService.diff(null, task, AUDIT_FIELDS));
        return task;
    }

    public Task update(int id, Task task) {
        Task before = taskMapper.getTaskById(id);
        if (before == null) throw new ResourceNotFoundException("Task not found with id: " + id);
        task.setId(id);
        taskMapper.update(task);
        auditService.record("task.update", "task", id, task.getDescription(),
            "Updated task " + task.getDescription(),
            auditService.diff(before, task, AUDIT_FIELDS));
        return task;
    }

    public void delete(int id) {
        Task before = taskMapper.getTaskById(id);
        if (before == null) throw new ResourceNotFoundException("Task not found with id: " + id);
        taskMapper.delete(id);
        auditService.record("task.delete", "task", id, before.getDescription(),
            "Deleted task " + before.getDescription(),
            auditService.diff(before, null, AUDIT_FIELDS));
    }
}
