package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.WorkflowVersion;

/** Workspace-scoped append-only persistence for immutable workflow versions. */
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

    WorkflowVersion getLatestForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId);

    void insert(WorkflowVersion version);
}
