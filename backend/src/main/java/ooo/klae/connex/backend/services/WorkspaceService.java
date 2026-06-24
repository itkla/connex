package ooo.klae.connex.backend.services;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Resolves the active workspace for the current request and exposes membership /
 * role primitives. Within a request the active workspace comes from
 * {@link TenantContext} (set by the resolution interceptor); off the request
 * thread (tests, scheduled jobs) it falls back to the caller's first membership.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceService {
    private final WorkspaceMapper workspaceMapper;
    private final RoleMapper roleMapper;
    private final TenantContext tenantContext;
    private final AuditService auditService;

    /** Built-in role permission bundles. Owner gets the full catalog. */
    private static final Set<Permission> MEMBER_PERMISSIONS = memberPermissions();
    private static final Set<Permission> ADMIN_PERMISSIONS = adminPermissions();
    private static final Set<Permission> OWNER_PERMISSIONS = EnumSet.allOf(Permission.class);

    private static EnumSet<Permission> memberPermissions() {
        return EnumSet.of(
            Permission.COMPANY_CREATE, Permission.COMPANY_UPDATE,
            Permission.PERSON_CREATE, Permission.PERSON_UPDATE, Permission.PERSON_DELETE,
            Permission.DEAL_CREATE, Permission.DEAL_UPDATE, Permission.DEAL_DELETE,
            Permission.ACTIVITY_CREATE, Permission.ACTIVITY_UPDATE, Permission.ACTIVITY_DELETE,
            Permission.NOTE_CREATE, Permission.NOTE_UPDATE, Permission.NOTE_DELETE,
            Permission.TASK_CREATE, Permission.TASK_UPDATE, Permission.TASK_DELETE,
            Permission.ATTACHMENT_CREATE, Permission.ATTACHMENT_DELETE);
    }

    private static EnumSet<Permission> adminPermissions() {
        EnumSet<Permission> permissions = memberPermissions();
        permissions.addAll(EnumSet.of(
            Permission.COMPANY_DELETE, Permission.PIPELINE_MANAGE, Permission.TAG_MANAGE,
            Permission.SHARE_MANAGE, Permission.MEMBER_MANAGE, Permission.AUDIT_READ,
            Permission.WORKSPACE_SETTINGS));
        return permissions;
    }

    @Value("${connex.workspaces.allow-self-service-creation:true}")
    private boolean selfServiceCreationAllowed;

    /** Workspace roles in ascending privilege order. */
    public enum Role {
        MEMBER, ADMIN, OWNER;

        static Role of(String value) {
            return value == null ? null : Role.valueOf(value.trim().toUpperCase());
        }
    }

    public int getCurrentWorkspaceId() {
        if (tenantContext.isResolved()) {
            return tenantContext.getWorkspaceId();
        }
        return fallbackWorkspaceId(currentUser().getId());
    }

    public Workspace getCurrentWorkspace() {
        int id = getCurrentWorkspaceId();
        return workspaceMapper.getWorkspacesForUser(currentUser().getId()).stream()
            .filter(w -> w.getId() == id)
            .findFirst()
            .orElseThrow(() -> new ForbiddenException("Active workspace is not accessible"));
    }

    /** The workspace to activate when none is supplied: remembered last-active, else first membership, else null. */
    public Integer defaultWorkspaceIdFor(int userId) {
        Integer last = workspaceMapper.getLastActiveWorkspaceId(userId);
        if (last != null && workspaceMapper.isMember(last, userId)) {
            return last;
        }
        List<Workspace> workspaces = workspaceMapper.getWorkspacesForUser(userId);
        return workspaces.isEmpty() ? null : workspaces.getFirst().getId();
    }

    private int fallbackWorkspaceId(int userId) {
        Integer id = defaultWorkspaceIdFor(userId);
        if (id == null) {
            throw new ForbiddenException("The authenticated user does not belong to a workspace");
        }
        return id;
    }

    public String getRole(int workspaceId, int userId) {
        return workspaceMapper.getRole(workspaceId, userId);
    }

    public List<WorkspaceMembershipDto> getMembershipsForCurrentUser() {
        return workspaceMapper.getMembershipsForUser(currentUser().getId());
    }

    public void rememberActive(int userId, int workspaceId) {
        workspaceMapper.setLastActiveWorkspaceId(userId, workspaceId);
    }

    public boolean isSelfServiceCreationAllowed() {
        return selfServiceCreationAllowed;
    }

    /**
     * Creates a workspace owned by the given user. Used by registration (when
     * self-service creation is enabled) and the create endpoint.
     */
    public WorkspaceMembershipDto createWorkspace(String name, int ownerUserId) {
        if (!selfServiceCreationAllowed) {
            throw new ForbiddenException("Workspace creation is disabled on this instance");
        }
        Workspace workspace = new Workspace();
        workspace.setName(name.trim());
        workspace.setSlug(generateSlug(name));
        workspaceMapper.insert(workspace);
        workspaceMapper.addMember(workspace.getId(), ownerUserId, "owner");
        return new WorkspaceMembershipDto(workspace.getId(), workspace.getName(), workspace.getSlug(), "owner");
    }

    private String generateSlug(String name) {
        String base = name.trim().toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
        if (base.isEmpty()) {
            base = "workspace";
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public void requireMember(int workspaceId, int userId) {
        if (!isMember(workspaceId, userId)) {
            throw new ForbiddenException("User " + userId + " is not a member of this workspace");
        }
    }

    public void requireRole(int workspaceId, int userId, Role min) {
        Role actual = Role.of(workspaceMapper.getRole(workspaceId, userId));
        if (actual == null || actual.ordinal() < min.ordinal()) {
            throw new ForbiddenException("Requires " + min + " role in this workspace");
        }
    }

    /** Requires the current user to hold at least {@code min} role in the active workspace. */
    public void requireRole(Role min) {
        requireRole(getCurrentWorkspaceId(), currentUser().getId(), min);
    }

    /**
     * The effective permission set for a member: their custom role's granted
     * permissions when one is assigned, otherwise their built-in role bundle.
     */
    public Set<Permission> permissionsFor(int workspaceId, int userId) {
        Integer roleId = workspaceMapper.getMemberRoleId(workspaceId, userId);
        if (roleId != null) {
            return parsePermissions(roleMapper.findPermissions(roleId));
        }
        Role role = Role.of(workspaceMapper.getRole(workspaceId, userId));
        if (role == null) {
            return EnumSet.noneOf(Permission.class);
        }
        return switch (role) {
            case OWNER -> OWNER_PERMISSIONS;
            case ADMIN -> ADMIN_PERMISSIONS;
            case MEMBER -> MEMBER_PERMISSIONS;
        };
    }

    public void requirePermission(int workspaceId, int userId, Permission permission) {
        if (!permissionsFor(workspaceId, userId).contains(permission)) {
            throw new ForbiddenException("Requires the " + permission + " permission in this workspace");
        }
    }

    /** Requires the current user to hold {@code permission} in the active workspace. */
    public void requirePermission(Permission permission) {
        requirePermission(getCurrentWorkspaceId(), currentUser().getId(), permission);
    }

    private static Set<Permission> parsePermissions(List<String> raw) {
        EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (String value : raw) {
            try {
                permissions.add(Permission.valueOf(value));
            } catch (IllegalArgumentException ignored) {
                // Unknown catalog key (e.g. a removed permission); skip it.
            }
        }
        return permissions;
    }

    /** Assigns a custom role to a member; managing roles requires the ROLE_MANAGE permission. */
    public MemberDto assignCustomRole(int workspaceId, int actorId, int targetUserId, int roleId) {
        requirePermission(workspaceId, actorId, Permission.ROLE_MANAGE);
        MemberDto target = workspaceMapper.getMember(workspaceId, targetUserId);
        if (target == null) {
            throw new ResourceNotFoundException("User is not a member of this workspace");
        }
        if (!roleMapper.roleExists(workspaceId, roleId)) {
            throw new ResourceNotFoundException("Role not found in this workspace");
        }
        workspaceMapper.setMemberCustomRole(workspaceId, targetUserId, roleId);
        auditService.record("workspace.member.role", "workspace", workspaceId, target.getDisplayName(),
                "Assigned a custom role to " + target.getDisplayName(), null);
        return workspaceMapper.getMember(workspaceId, targetUserId);
    }

    public boolean isMember(int workspaceId, int userId) {
        return workspaceMapper.isMember(workspaceId, userId);
    }

    public List<User> getMembers(int workspaceId) {
        return workspaceMapper.getMembers(workspaceId);
    }

    /** Lists the workspace's members with their roles. Visible to any member. */
    public List<MemberDto> getMembersWithRoles(int workspaceId, int actorId) {
        requireMember(workspaceId, actorId);
        return workspaceMapper.getMembersWithRoles(workspaceId);
    }

    /**
     * Changes a member's role. Admins manage member/admin; only an owner may grant
     * ownership, and the last owner cannot be demoted.
     */
    public MemberDto changeMemberRole(int workspaceId, int actorId, int targetUserId, String roleRaw) {
        requirePermission(workspaceId, actorId, Permission.MEMBER_MANAGE);
        Role newRole = parseAssignableRole(roleRaw);
        MemberDto target = workspaceMapper.getMember(workspaceId, targetUserId);
        if (target == null) {
            throw new ResourceNotFoundException("User is not a member of this workspace");
        }
        if (newRole == Role.OWNER) {
            requireRole(workspaceId, actorId, Role.OWNER);
        }
        if ("owner".equals(target.getRole()) && newRole != Role.OWNER
                && workspaceMapper.countOwners(workspaceId) <= 1) {
            throw new BadRequestException("A workspace must keep at least one owner");
        }
        workspaceMapper.updateMemberRole(workspaceId, targetUserId, newRole.name().toLowerCase());
        auditService.record("workspace.member.role", "workspace", workspaceId, target.getDisplayName(),
                "Changed " + target.getDisplayName() + " to " + newRole.name().toLowerCase(), null);
        return workspaceMapper.getMember(workspaceId, targetUserId);
    }

    /**
     * Removes a member, unassigning their tasks and clearing their deal ownership
     * first so authored history survives. Only an owner may remove an owner, and
     * never the last one.
     */
    public void removeMember(int workspaceId, int actorId, int targetUserId) {
        requirePermission(workspaceId, actorId, Permission.MEMBER_MANAGE);
        MemberDto target = workspaceMapper.getMember(workspaceId, targetUserId);
        if (target == null) {
            throw new ResourceNotFoundException("User is not a member of this workspace");
        }
        if ("owner".equals(target.getRole())) {
            requireRole(workspaceId, actorId, Role.OWNER);
            if (workspaceMapper.countOwners(workspaceId) <= 1) {
                throw new BadRequestException("A workspace must keep at least one owner");
            }
        }
        workspaceMapper.unassignMemberTasks(workspaceId, targetUserId);
        workspaceMapper.clearMemberDealOwnership(workspaceId, targetUserId);
        workspaceMapper.removeMember(workspaceId, targetUserId);
        auditService.record("workspace.member.remove", "workspace", workspaceId, target.getDisplayName(),
                "Removed " + target.getDisplayName() + " from the workspace", null);
    }

    private Role parseAssignableRole(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Role is required");
        }
        try {
            return Role.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Role must be owner, admin, or member");
        }
    }

    private User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof User user)) {
            throw new ForbiddenException("Authentication is required to resolve a workspace");
        }
        return user;
    }
}
