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
import ooo.klae.connex.backend.dto.TaskDto;
import ooo.klae.connex.backend.dto.TaskMoveRequest;
import ooo.klae.connex.backend.services.TaskService;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for {@code Task} CRUD operations.
 * A Task is a to-do assigned to a {@code User}, optionally linked to a {@code Person} and/or
 * {@code Deal}.
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
    public List<TaskDto> getTasks(
        @RequestParam(required = false) Integer assignedToId,
        @RequestParam(required = false) Integer personId,
        @RequestParam(required = false) Integer dealId
    ) {
        List<Task> tasks;
        if (assignedToId != null) tasks = taskService.getTasksByAssignedToId(assignedToId);
        else if (personId != null) tasks = taskService.getTasksByPersonId(personId);
        else if (dealId != null) tasks = taskService.getTasksByDealId(dealId);
        else tasks = taskService.getAllTasks();
        return tasks.stream().map(TaskDto::from).toList();
    }

    /**
     * GET endpoint to retrieve a single task by ID.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public TaskDto getTaskById(@PathVariable int id) {
        return TaskDto.from(taskService.getTaskById(id));
    }

    /**
     * POST endpoint to create a new task.
     * @param dto
     * @return
     */
    @PostMapping
    public TaskDto createTask(@Valid @RequestBody TaskDto dto) {
        return TaskDto.from(taskService.create(dto.toBean()));
    }

    /**
     * PUT endpoint to update an existing task.
     * @param id
     * @param dto
     * @return
     */
    @PutMapping("/{id}")
    public TaskDto updateTask(@PathVariable int id, @Valid @RequestBody TaskDto dto) {
        return TaskDto.from(taskService.update(id, dto.toBean()));
    }

    /**
     * DELETE endpoint to delete a task by ID.
     * @param id
     */
    @DeleteMapping("/{id}")
    public void deleteTask(@PathVariable int id) {
        taskService.delete(id);
    }

    @PostMapping("/{id}/complete")
    public TaskDto completeTask(@PathVariable int id) {
        return TaskDto.from(taskService.complete(id));
    }

    /**
     * POST endpoint to move a task to a target status column and ordinal position on the Kanban board.
     * @param id the task to move
     * @param req the target status and 0-based position within that status column
     * @return the moved task
     */
    @PostMapping("/{id}/move")
    public TaskDto moveTask(@PathVariable int id, @Valid @RequestBody TaskMoveRequest req) {
        return TaskDto.from(taskService.move(id, req.getStatus(), req.getPosition()));
    }
}
