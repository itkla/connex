package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.WorkspaceRole;

/**
 * Persistence for custom workspace roles and their granted permissions.
 * Control-plane (identity/authorization), scoped by explicit workspace id.
 */
public interface RoleMapper {
    int insertRole(WorkspaceRole role);
    List<WorkspaceRole> findRolesByWorkspace(int workspaceId);
    WorkspaceRole findRole(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int updateRoleName(@Param("workspaceId") int workspaceId, @Param("id") int id, @Param("name") String name);
    int deleteRole(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<String> findPermissions(int roleId);
    int clearPermissions(int roleId);
    int insertPermissions(@Param("roleId") int roleId, @Param("permissions") List<String> permissions);
    boolean roleExists(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
