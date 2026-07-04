package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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

    public List<WorkspaceRole> builtInRoles(int workspaceId, int actorId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.ROLE_MANAGE);
        return workspaceService.builtInRoles();
    }

    @Transactional
    public WorkspaceRole createRole(int workspaceId, int actorId, String name, List<String> permissions) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.ROLE_MANAGE);
        List<String> valid = validatePermissions(permissions);
        workspaceService.requireGrantable(workspaceId, actorId, toEnumSet(valid));
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspaceId);
        role.setName(name.trim());
        roleMapper.insertRole(role);
        applyPermissions(workspaceId, role.getId(), valid);
        auditService.record("workspace.role.create", "workspace", workspaceId, name,
                "Created role " + name, null);
        return roleMapper.findRole(workspaceId, role.getId());
    }

    @Transactional
    public WorkspaceRole updateRole(int workspaceId, int actorId, int roleId, String name, List<String> permissions) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.ROLE_MANAGE);
        List<String> valid = validatePermissions(permissions);
        workspaceService.requireGrantable(workspaceId, actorId, toEnumSet(valid));
        if (roleMapper.updateRoleName(workspaceId, roleId, name.trim()) == 0) {
            throw new ResourceNotFoundException("Role not found");
        }
        applyPermissions(workspaceId, roleId, valid);
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

    private void applyPermissions(int workspaceId, int roleId, List<String> validPermissions) {
        roleMapper.clearPermissions(workspaceId, roleId);
        if (!validPermissions.isEmpty()) {
            roleMapper.insertPermissions(workspaceId, roleId, validPermissions);
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

    private static Set<Permission> toEnumSet(List<String> validPermissionNames) {
        EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (String name : validPermissionNames) {
            permissions.add(Permission.valueOf(name));
        }
        return permissions;
    }
}
