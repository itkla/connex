package ooo.klae.connex.backend.mappers;

import ooo.klae.connex.backend.beans.Task;

import java.util.List;

/**
 * Mapper interface for {@code Task} persistence.
 * SQL is defined in {@code resources/mappers/TaskMapper.xml}.
 * Used by {@code TaskService}.
 */

public interface TaskMapper {
    List<Task> getAllTasks();
    List<Task> getTasksByAssignedToId(int assignedToId);
    List<Task> getTasksByPersonId(int personId);
    List<Task> getTasksByDealId(int dealId);
    Task getTaskById(int id);
    int insert(Task task);
    int update(Task task);
    int delete(int id);
}
