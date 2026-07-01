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
        @Param("assignedToId") int assignedToId,
        @Param("position") int position
    );

    /** Task ids in a status column, in board order (position, then id), for renumbering on a move. */
    List<Integer> getTaskIdsInStatusOrdered(@Param("workspaceId") int workspaceId, @Param("status") String status);
    /** The next free tail position in a status column ({@code MAX(position)+1}, or 0 when empty). */
    int nextTaskPosition(@Param("workspaceId") int workspaceId, @Param("status") String status);
    /** Sets a single task's manual sort position within its status column. */
    int setPosition(@Param("workspaceId") int workspaceId, @Param("id") int id, @Param("position") int position);
    /** Sets a task's status, completion flag and position together so the done/completed CHECK holds. */
    int moveTask(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("status") String status,
        @Param("completed") boolean completed,
        @Param("position") int position
    );
}
