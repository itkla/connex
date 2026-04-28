package ooo.klae.connex.backend.services;

import java.util.List;

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
        return task;
    }

    public Task update(int id, Task task) {
        if (taskMapper.getTaskById(id) == null) throw new ResourceNotFoundException("Task not found with id: " + id);
        task.setId(id);
        taskMapper.update(task);
        return task;
    }

    public void delete(int id) {
        if (taskMapper.getTaskById(id) == null) throw new ResourceNotFoundException("Task not found with id: " + id);
        taskMapper.delete(id);
    }
}
