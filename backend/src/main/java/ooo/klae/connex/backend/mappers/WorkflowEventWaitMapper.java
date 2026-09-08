package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.TaskCompletionEvent;
import ooo.klae.connex.backend.beans.WorkflowEventWait;

/** Tenant-scoped task completion evidence and correlated workflow event waits. */
public interface WorkflowEventWaitMapper {

    void insertTaskCompletionEvent(
        @Param("workspaceId") int workspaceId,
        @Param("taskId") int taskId);

    TaskCompletionEvent getEarliestCompletion(
        @Param("workspaceId") int workspaceId,
        @Param("taskId") int taskId,
        @Param("timeoutAt") LocalDateTime timeoutAt);

    void insertWait(WorkflowEventWait wait);

    WorkflowEventWait getByRunNodeForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("workflowRunId") long workflowRunId,
        @Param("nodeId") String nodeId);

    List<WorkflowEventWait> getByRun(
        @Param("workspaceId") int workspaceId,
        @Param("workflowRunId") long workflowRunId);

    int resolve(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("resolution") String resolution,
        @Param("matchedEventId") Long matchedEventId,
        @Param("resolvedAt") LocalDateTime resolvedAt);

    int resolveCurrent(
        @Param("workspaceId") int workspaceId,
        @Param("workflowRunId") long workflowRunId,
        @Param("nodeId") String nodeId,
        @Param("resolution") String resolution,
        @Param("resolvedAt") LocalDateTime resolvedAt);
}
