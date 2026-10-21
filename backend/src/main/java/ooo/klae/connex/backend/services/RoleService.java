package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
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
    private final SessionSecurityService sessionSecurityService;
    private final PrivilegedAccountService privilegedAccountService;
    private final OwnershipOfferWriter ownershipOfferWriter;

    public List<WorkspaceRole> listRoles(int workspaceId, int actorId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.ROLE_MANAGE);
        return roleMapper.findRolesByWorkspace(workspaceId);
    }

    public List<WorkspaceRole> builtInRoles(int workspaceId, int actorId) {
        Set<Permission> permissions = workspaceService.permissionsFor(workspaceId, actorId);
        if (!permissions.contains(Permission.MEMBER_MANAGE)
                && !permissions.contains(Permission.ROLE_MANAGE)) {
            throw new ForbiddenException(
                "Requires member or role management permission in this workspace");
        }
        return workspaceService.builtInRoles();
    }

    @Transactional
    public WorkspaceRole createRole(int workspaceId, int actorId, String name, List<String> permissions) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.ROLE_MANAGE);
        sessionSecurityService.requireRecentAuthentication(actorId);
        List<String> valid = validatePermissions(permissions);
        workspaceService.lockRoleCreationAuthorization(
            workspaceId, actorId, toEnumSet(valid));
        WorkspaceRole role = new WorkspaceRole();
        role.setWorkspaceId(workspaceId);
        role.setName(name.trim());
        roleMapper.insertRole(role);
        applyPermissions(workspaceId, role.getId(), valid);
        auditService.record("workspace.role.create", "workspace", workspaceId, name,
                "Created role " + name, null);
        return roleMapper.findRole(workspaceId, role.getId());
    }

    /**
     * Updates a role without acknowledging confinement; see
     * {@link #updateRole(int, int, int, String, List, boolean)}.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkspaceRole updateRole(int workspaceId, int actorId, int roleId, String name, List<String> permissions) {
        return updateRole(workspaceId, actorId, roleId, name, permissions, false);
    }

    /**
     * Renames a role and replaces its permissions. Once attestation is enforced, an edit that makes
     * any assignee newly privileged in the organization without a passkey approved there is refused
     * unless acknowledged (#1534). The assignees are those the role-mutation locks hold, read in a
     * few set-based statements however many there are. Runs at {@code READ COMMITTED} so that check
     * reads current state.
     *
     * @param acknowledgeConfinement whether the actor accepts that assignees may be confined
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkspaceRole updateRole(int workspaceId, int actorId, int roleId, String name, List<String> permissions,
            boolean acknowledgeConfinement) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.ROLE_MANAGE);
        sessionSecurityService.requireRecentAuthentication(actorId);
        List<String> valid = validatePermissions(permissions);
        List<Integer> assignees = workspaceService.lockRoleMutationAuthorization(
            workspaceId, actorId, roleId, toEnumSet(valid));
        ConfinementBaseline baseline =
            privilegedAccountService.confinementBaseline(() -> workspaceService.getOrgId(workspaceId), assignees);
        if (roleMapper.updateRoleName(workspaceId, roleId, name.trim()) == 0) {
            throw new ResourceNotFoundException("Role not found");
        }
        applyPermissions(workspaceId, roleId, valid);
        privilegedAccountService.requireConfinementAcknowledged(baseline, acknowledgeConfinement);
        ownershipOfferWriter.invalidateScopeLocked(workspaceService.getOrgId(workspaceId), workspaceId);
        auditService.record("workspace.role.update", "workspace", workspaceId, name,
                "Updated role " + name, null);
        return roleMapper.findRole(workspaceId, roleId);
    }

    @Transactional
    public void deleteRole(int workspaceId, int actorId, int roleId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.ROLE_MANAGE);
        sessionSecurityService.requireRecentAuthentication(actorId);
        workspaceService.lockRoleDeletionAuthorization(workspaceId, actorId, roleId);
        if (roleMapper.deleteRole(workspaceId, roleId) == 0) {
            throw new ResourceNotFoundException("Role not found");
        }
        auditService.record("workspace.role.delete", "workspace", workspaceId, null,
                "Deleted role " + roleId, null);
    }

    private void applyPermissions(int workspaceId, int roleId, List<String> validPermissions) {
        Set<String> current = Set.copyOf(roleMapper.lockPermissions(workspaceId, roleId));
        if (current.equals(Set.copyOf(validPermissions))) {
            return;
        }
        if (roleMapper.advancePermissionEpoch(workspaceId, roleId) != 1) {
            throw new IllegalStateException("Role permission epoch could not advance");
        }
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
                Permission permission = Permission.valueOf(value);
                if (!Permission.isGrantable(permission)) {
                    throw new BadRequestException("Permission is not grantable: " + value);
                }
                String name = permission.name();
                if (!valid.contains(name)) {
                    valid.add(name);
                }
            } catch (IllegalArgumentException | NullPointerException e) {
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
