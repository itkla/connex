package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.services.TaskService;

import java.util.List;

import lombok.RequiredArgsConstructor;

/**
 * REST controller for {@code Task} CRUD operations.
 * Accepts and returns {@code TaskDto}. Delegates to {@code TaskService}.
 */

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskService taskService;

    /**
     * GET endpoint to retrieve tasks, with optional filtering by assignedToId, personId, or dealId.
     * @param assignedToId
     * @param personId
     * @param dealId
     * @return
     */
    @GetMapping
    public List<Task> getTasks(
        @RequestParam(required = false) Integer assignedToId,
        @RequestParam(required = false) Integer personId,
        @RequestParam(required = false) Integer dealId
    ) {
        if (assignedToId != null) return taskService.getTasksByAssignedToId(assignedToId);
        if (personId != null)     return taskService.getTasksByPersonId(personId);
        if (dealId != null)       return taskService.getTasksByDealId(dealId);
        return taskService.getAllTasks();
    }

    /**
     * GET endpoint to retrieve a single task by ID.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public Task getTaskById(@PathVariable int id) {
        return taskService.getTaskById(id);
    }

    // TODO: add filtering by companyId, userId, etc. once those concepts are implemented

    /**
     * POST endpoint to create a new task.
     * @param task
     * @return
     */
    @PostMapping
    public Task createTask(@RequestBody Task task) {
        return taskService.create(task);
    }

    /**
     * PUT endpoint to update an existing task.
     * @param id
     * @param task
     * @return
     */
    @PutMapping("/{id}")
    public Task updateTask(@PathVariable int id, @RequestBody Task task) {
        return taskService.update(id, task);
    }

    /**
     * DELETE endpoint to delete a task by ID.
     * @param id
     */
    @DeleteMapping("/{id}")
    public void deleteTask(@PathVariable int id) {
        taskService.delete(id);
    }
}
