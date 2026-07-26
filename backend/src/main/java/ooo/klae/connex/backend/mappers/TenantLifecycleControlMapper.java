package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.dto.OrganizationLifecycleRef;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;

/** Control-plane persistence for tenant lifecycle roots, leases, and terminal deletion. */
public interface TenantLifecycleControlMapper {

    WorkspaceLifecycleRef findWorkspaceInOrg(
        @Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId);

    Integer findWorkspaceOrgIdForLifecycle(@Param("workspaceId") int workspaceId);

    WorkspaceLifecycleRef lockActiveWorkspaceForExport(
        @Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId);

    WorkspaceLifecycleRef lockWorkspaceInOrg(
        @Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId);

    OrganizationLifecycleRef findOrganization(@Param("orgId") int orgId);

    OrganizationLifecycleRef lockOrganization(@Param("orgId") int orgId);

    List<WorkspaceLifecycleRef> findWorkspacesInOrgAfter(
        @Param("orgId") int orgId,
        @Param("afterWorkspaceId") int afterWorkspaceId,
        @Param("limit") int limit);

    boolean isOrgOwnerForLifecycle(
        @Param("orgId") int orgId,
        @Param("userId") int userId);

    int insertOperationLease(
        @Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId,
        @Param("leaseKind") String leaseKind,
        @Param("leaseToken") String leaseToken);

    int deleteOperationLease(
        @Param("workspaceId") int workspaceId,
        @Param("leaseKind") String leaseKind,
        @Param("leaseToken") String leaseToken);

    int countOperationLeases(
        @Param("workspaceId") int workspaceId,
        @Param("leaseKind") String leaseKind);

    int markWorkspaceTearingDown(
        @Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId);

    int markOrganizationTearingDown(@Param("orgId") int orgId);

    int clearSsoJitWorkspace(
        @Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId);

    int deleteFederatedIdentityBatch(
        @Param("orgId") int orgId,
        @Param("limit") int limit);

    int deleteWorkspace(
        @Param("orgId") int orgId,
        @Param("workspaceId") int workspaceId);

    int deleteOrganization(@Param("orgId") int orgId);

    int countWorkspaces(@Param("orgId") int orgId);
}
