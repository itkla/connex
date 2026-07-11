package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.TaskSummaryDto;

import java.util.List;

/**
 * Mapper interface for {@code Task} persistence.
 * SQL is defined in {@code resources/mappers/TaskMapper.xml}.
 * Used by {@code TaskService}.
 */

public interface TaskMapper {
    List<Task> getAllTasks(int workspaceId);
    List<Task> getTasksPage(@Param("workspaceId") int workspaceId, @Param("limit") int limit, @Param("offset") int offset);
    long countTasks(int workspaceId);
    TaskSummaryDto taskSummary(int workspaceId);
    List<Task> getTasksByAssignedToId(
        @Param("workspaceId") int workspaceId,
        @Param("assignedToId") int assignedToId
    );
    List<Task> getTasksByPersonId(@Param("workspaceId") int workspaceId, @Param("personId") int personId);
    List<Task> getTasksByPersonIds(@Param("workspaceId") int workspaceId,
            @Param("personIds") List<Integer> personIds);
    List<Task> getTasksByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    Task getTaskById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
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
    /** Sets only a task's due date, scoped to the workspace. */
    int updateDueDate(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("dueDate") String dueDate
    );

    /**
     * Unassigns a member's tasks within one workspace. Moved from
     * {@code WorkspaceMapper} so the control plane never writes org-data tables
     * (#440 increment 3).
     */
    void unassignMemberTasks(@Param("workspaceId") int workspaceId, @Param("userId") int userId);

    /**
     * Unassigns a user's tasks across all workspaces. Offboarding replacement
     * for the {@code task.assigned_to_id} ON DELETE SET NULL (#440 increment 3).
     */
    void unassignAnywhere(@Param("userId") int userId);
}
