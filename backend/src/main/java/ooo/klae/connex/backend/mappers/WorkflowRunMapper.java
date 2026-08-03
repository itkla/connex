package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowRunView;
import ooo.klae.connex.backend.beans.WorkflowStepRun;

/** Workspace-scoped canonical workflow run and per-node checkpoint persistence. */
public interface WorkflowRunMapper {

    void insertRun(WorkflowRun run);

    WorkflowRun getByDedupe(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("dedupeKey") String dedupeKey);

    WorkflowRun getById(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("id") long id);

    WorkflowRun getByIdInWorkspace(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    WorkflowRun getByIdForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    List<WorkflowRun> getRunningByTrigger(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("triggerKey") String triggerKey,
        @Param("limit") int limit);

    int nextSequence(
        @Param("workspaceId") int workspaceId,
        @Param("workflowRunId") long workflowRunId);

    void insertStep(WorkflowStepRun step);

    int advanceRun(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("expectedNodeId") String expectedNodeId,
        @Param("nextNodeId") String nextNodeId);

    int completeRun(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("expectedNodeId") String expectedNodeId,
        @Param("finishedAt") LocalDateTime finishedAt);

    int failRun(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("expectedNodeId") String expectedNodeId,
        @Param("status") String status,
        @Param("failureCode") String failureCode,
        @Param("failureMessage") String failureMessage,
        @Param("finishedAt") LocalDateTime finishedAt);

    LocalDateTime currentTimestamp(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId);

    List<WorkflowRunView> getPage(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("asOf") LocalDateTime asOf,
        @Param("beforeStartedAt") LocalDateTime beforeStartedAt,
        @Param("beforeId") Long beforeId,
        @Param("limit") int limit);

    WorkflowRunView getViewById(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("id") long id);

    List<WorkflowStepRun> getSteps(
        @Param("workspaceId") int workspaceId,
        @Param("workflowRunId") long workflowRunId);
}
