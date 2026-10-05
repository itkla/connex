package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.WorkspaceRole;

/**
 * Persistence for custom workspace roles and their granted permissions. Every
 * statement — including the permission statements, which anchor through the
 * parent {@code workspace_role} — binds the workspace id, so the namespace sits
 * in the tenant-scoped interceptor registry.
 */
public interface RoleMapper {
    int insertRole(WorkspaceRole role);
    List<WorkspaceRole> findRolesByWorkspace(int workspaceId);
    WorkspaceRole findRole(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Integer lockRole(@Param("workspaceId") int workspaceId, @Param("id") int id);
    /**
     * Locks and returns every permission row of one custom role, inert ones included, reading no other
     * role's rows (#1578). Callers lock the role with {@link #lockRole} first and drop the permissions
     * {@code Permission.isGrantable} rejects.
     * @param workspaceId the owning workspace
     * @param roleId the custom role
     * @return the role's permission names in ascending order
     */
    List<String> lockPermissions(
        @Param("workspaceId") int workspaceId,
        @Param("roleId") int roleId);
    int updateRoleName(@Param("workspaceId") int workspaceId, @Param("id") int id, @Param("name") String name);
    int deleteRole(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<String> findPermissions(@Param("workspaceId") int workspaceId, @Param("roleId") int roleId);
    int clearPermissions(@Param("workspaceId") int workspaceId, @Param("roleId") int roleId);
    int insertPermissions(@Param("workspaceId") int workspaceId, @Param("roleId") int roleId,
            @Param("permissions") List<String> permissions);
}
