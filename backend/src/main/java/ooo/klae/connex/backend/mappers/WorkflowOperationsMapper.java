package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.WorkflowBacklogView;
import ooo.klae.connex.backend.beans.WorkflowIntervention;
import ooo.klae.connex.backend.beans.WorkflowInvocation;
import ooo.klae.connex.backend.beans.WorkflowInvocationRecord;
import ooo.klae.connex.backend.beans.WorkflowOperationsRunView;
import ooo.klae.connex.backend.beans.WorkflowOperationsSummaryView;
import ooo.klae.connex.backend.beans.WorkflowRecipeOrigin;

/** Tenant-scoped persistence for workflow operations, recipes, and exact invocations. */
public interface WorkflowOperationsMapper {

    WorkflowOperationsSummaryView getSummary(@Param("workspaceId") int workspaceId);

    WorkflowBacklogView getBacklog(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId);

    List<WorkflowOperationsRunView> getOperationsRuns(
        @Param("workspaceId") int workspaceId,
        @Param("status") String status,
        @Param("failureCategory") String failureCategory,
        @Param("ownerId") Integer ownerId,
        @Param("beforeStartedAt") LocalDateTime beforeStartedAt,
        @Param("beforeId") Long beforeId,
        @Param("limit") int limit);

    void insertRecipeOrigin(WorkflowRecipeOrigin origin);

    WorkflowRecipeOrigin getRecipeOrigin(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId);

    void insertInvocation(WorkflowInvocation invocation);

    void insertInvocationRecords(
        @Param("workspaceId") int workspaceId,
        @Param("invocationId") long invocationId,
        @Param("records") List<WorkflowInvocationRecord> records);

    WorkflowInvocation getInvocation(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("id") long id);

    WorkflowInvocation getInvocationByTokenForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("tokenHash") byte[] tokenHash);

    WorkflowInvocation getInvocationForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("id") long id);

    List<WorkflowInvocationRecord> getInvocationRecords(
        @Param("workspaceId") int workspaceId,
        @Param("invocationId") long invocationId);

    int confirmInvocation(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("requestedById") int requestedById,
        @Param("confirmationKey") byte[] confirmationKey,
        @Param("confirmedAt") LocalDateTime confirmedAt);

    int linkInvocationRun(
        @Param("workspaceId") int workspaceId,
        @Param("invocationId") long invocationId,
        @Param("recordId") int recordId,
        @Param("workflowRunId") long workflowRunId);

    int markInvocationRecordSkipped(
        @Param("workspaceId") int workspaceId,
        @Param("invocationId") long invocationId,
        @Param("recordId") int recordId,
        @Param("category") String category);

    int refreshInvocationRecords(
        @Param("workspaceId") int workspaceId,
        @Param("invocationId") long invocationId);

    int updateInvocationStatus(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("status") String status,
        @Param("completedAt") LocalDateTime completedAt);

    int completeInvocationIfActive(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("status") String status,
        @Param("completedAt") LocalDateTime completedAt);

    int markInvocationRunning(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    int cancelInvocation(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("completedAt") LocalDateTime completedAt);

    int cancelPendingInvocationRecords(
        @Param("workspaceId") int workspaceId,
        @Param("invocationId") long invocationId);

    void upsertIntervention(WorkflowIntervention intervention);

    WorkflowIntervention getIntervention(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    WorkflowIntervention getInterventionForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    List<WorkflowIntervention> getOpenInterventionsByWorkflow(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("limit") int limit);

    int updateInterventionOwner(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("ownerUserId") Integer ownerUserId,
        @Param("expectedSourceVersion") int expectedSourceVersion);

    int resolveIntervention(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("status") String status,
        @Param("expectedSourceVersion") int expectedSourceVersion);

    int resolveOpenInterventionsForRun(
        @Param("workspaceId") int workspaceId,
        @Param("workflowRunId") long workflowRunId,
        @Param("status") String status);

    int clearUserReferencesAnywhere(@Param("userId") int userId);
}
