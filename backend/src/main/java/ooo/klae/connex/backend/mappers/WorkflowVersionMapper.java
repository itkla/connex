package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.WorkflowVersion;

/** Workspace-scoped immutable workflow versions with a permanent-erasure identity redaction seam. */
public interface WorkflowVersionMapper {

    WorkflowVersion getById(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("id") long id);

    WorkflowVersion getByIdForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("id") long id);

    List<WorkflowVersion> listByWorkflow(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId);

    WorkflowVersion getLatest(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId);

    List<WorkflowVersion> findLockCandidatesByUserAnywhere(@Param("userId") int userId);

    void insert(WorkflowVersion version);

    int redactUserReferences(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("id") long id,
        @Param("userId") int userId);
}
