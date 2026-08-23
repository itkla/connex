package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.HistoryImportProvenance;
import ooo.klae.connex.backend.beans.HistoryImportWrite;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.BoardPositionUpdate;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.TaskSummaryDto;

import java.time.LocalDate;
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
    TaskSummaryDto taskSummary(
        @Param("workspaceId") int workspaceId,
        @Param("today") LocalDate today,
        @Param("memberScope") MemberScope memberScope
    );
    List<Task> getUpcomingOpenTasks(@Param("workspaceId") int workspaceId, @Param("limit") int limit);
    List<Task> getTasksByAssignedToId(
        @Param("workspaceId") int workspaceId,
        @Param("assignedToId") int assignedToId
    );
    List<Task> getTasksByPersonId(@Param("workspaceId") int workspaceId, @Param("personId") int personId);
    List<Task> getAiAssistantTasksByPersonId(
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("organizationWorkspaceIds") List<Integer> organizationWorkspaceIds,
        @Param("limit") int limit);
    List<Task> getTasksByPersonIds(@Param("workspaceId") int workspaceId,
            @Param("personIds") List<Integer> personIds);
    List<Task> getTasksByPersonCompanyIds(
        @Param("workspaceId") int workspaceId,
        @Param("personIds") List<Integer> personIds,
        @Param("companyIds") List<Integer> companyIds
    );
    List<Task> getTasksByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    List<Task> getAiAssistantTasksByDealId(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("organizationWorkspaceIds") List<Integer> organizationWorkspaceIds,
        @Param("limit") int limit);
    List<Task> getCompanyTasks(@Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId, @Param("limit") int limit);
    List<Task> getAiAssistantTasksByCompanyId(
        @Param("workspaceId") int workspaceId,
        @Param("companyId") int companyId,
        @Param("organizationWorkspaceIds") List<Integer> organizationWorkspaceIds,
        @Param("limit") int limit);
    List<Task> getTasksByDealCompanyIds(@Param("workspaceId") int workspaceId,
            @Param("companyIds") List<Integer> companyIds);
    Task getTaskById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Task getTaskByIdForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Integer> getVisibleIdsIn(
        @Param("workspaceId") int workspaceId,
        @Param("ids") List<Integer> ids
    );
    List<Task> search(
        @Param("workspaceId") int workspaceId,
        @Param("query") String query,
        @Param("limit") int limit,
        @Param("offset") int offset
    );
    int insert(Task task);
    List<HistoryImportProvenance> findHistoryImports(
        @Param("workspaceId") int workspaceId,
        @Param("historyImportKeys") List<String> historyImportKeys
    );
    int insertHistoryBatch(
        @Param("workspaceId") int workspaceId,
        @Param("rows") List<HistoryImportWrite> rows
    );
    int update(Task task);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int complete(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("assignedToId") int assignedToId,
        @Param("position") int position
    );

    /** Acquires the exact workspace task-board root through an atomic insert-or-update. */
    void lockTaskBoard(@Param("workspaceId") int workspaceId);
    /** Task ids in a status column, in board order (position, then id), for renumbering on a move. */
    List<Integer> getTaskIdsInStatusOrdered(@Param("workspaceId") int workspaceId, @Param("status") String status);
    /** Discovers candidate task ids before exact board-move locks are acquired in Java-sorted order. */
    List<Integer> listWorkspaceTaskIds(@Param("workspaceId") int workspaceId);
    /** The next free tail position in a status column ({@code MAX(position)+1}, or 0 when empty). */
    int nextTaskPosition(@Param("workspaceId") int workspaceId, @Param("status") String status);
    /** Sets manual sort positions for tasks that still belong to the expected workspace status. */
    int setPositions(
        @Param("workspaceId") int workspaceId,
        @Param("status") String status,
        @Param("positions") List<BoardPositionUpdate> positions
    );
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
