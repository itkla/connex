package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Manages owner-defined custom roles and their granted permissions. Every
 * mutation requires the actor to hold the ROLE_MANAGE permission.
 */
@Service
@RequiredArgsConstructor
public class RoleService {
    private final RoleMapper roleMapper;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;

    public List<WorkspaceRole> listRoles(int workspaceId, int actorId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.ROLE_MANAGE);
        return roleMapper.findRolesByWorkspace(workspaceId);
    }

    @Transactional
    public WorkspaceRole createRole(int workspaceId, int actorId, String name, List<String> permissions) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.ROLE_MANAGE);
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspaceId);
        role.setName(name.trim());
        roleMapper.insertRole(role);
        applyPermissions(role.getId(), permissions);
        auditService.record("workspace.role.create", "workspace", workspaceId, name,
                "Created role " + name, null);
        return roleMapper.findRole(workspaceId, role.getId());
    }

    @Transactional
    public WorkspaceRole updateRole(int workspaceId, int actorId, int roleId, String name, List<String> permissions) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.ROLE_MANAGE);
        if (roleMapper.updateRoleName(workspaceId, roleId, name.trim()) == 0) {
            throw new ResourceNotFoundException("Role not found");
        }
        applyPermissions(roleId, permissions);
        auditService.record("workspace.role.update", "workspace", workspaceId, name,
                "Updated role " + name, null);
        return roleMapper.findRole(workspaceId, roleId);
    }

    public void deleteRole(int workspaceId, int actorId, int roleId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.ROLE_MANAGE);
        if (roleMapper.deleteRole(workspaceId, roleId) == 0) {
            throw new ResourceNotFoundException("Role not found");
        }
        auditService.record("workspace.role.delete", "workspace", workspaceId, null,
                "Deleted role " + roleId, null);
    }

    private void applyPermissions(int roleId, List<String> permissions) {
        roleMapper.clearPermissions(roleId);
        List<String> valid = validatePermissions(permissions);
        if (!valid.isEmpty()) {
            roleMapper.insertPermissions(roleId, valid);
        }
    }

    private static List<String> validatePermissions(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> valid = new ArrayList<>();
        for (String value : raw) {
            try {
                valid.add(Permission.valueOf(value).name());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown permission: " + value);
            }
        }
        return valid;
    }
}
