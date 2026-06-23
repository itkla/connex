package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Task;

import java.util.List;

/**
 * Mapper interface for {@code Task} persistence.
 * SQL is defined in {@code resources/mappers/TaskMapper.xml}.
 * Used by {@code TaskService}.
 */

public interface TaskMapper {
    List<Task> getAllTasks(int workspaceId);
    List<Task> getTasksByAssignedToId(
        @Param("workspaceId") int workspaceId,
        @Param("assignedToId") int assignedToId
    );
    List<Task> getTasksByPersonId(@Param("workspaceId") int workspaceId, @Param("personId") int personId);
    List<Task> getTasksByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    Task getTaskById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Task> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    int insert(Task task);
    int update(Task task);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int complete(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("assignedToId") int assignedToId
    );
}
