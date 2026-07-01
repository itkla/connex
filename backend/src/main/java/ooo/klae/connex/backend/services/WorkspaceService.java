package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
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
    private final OrganizationMapper organizationMapper;
    private final RoleMapper roleMapper;
    private final NotificationMapper notificationMapper;
    private final TenantContext tenantContext;
    private final AuditService auditService;
    private final SystemActor systemActor;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
            Permission.CUSTOM_FIELD_MANAGE, Permission.SHARE_MANAGE, Permission.MEMBER_MANAGE,
            Permission.AUDIT_READ, Permission.WORKSPACE_SETTINGS, Permission.RULE_MANAGE));
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

    /** The organization owning the active workspace. */
    public int getCurrentOrgId() {
        if (tenantContext.isResolved()) {
            return tenantContext.getOrgId();
        }
        return getOrgId(fallbackWorkspaceId(currentUser().getId()));
    }

    /** The organization that owns the given workspace. */
    public int getOrgId(int workspaceId) {
        Integer orgId = workspaceMapper.getOrgId(workspaceId);
        if (orgId == null) {
            throw new ResourceNotFoundException("Workspace not found: " + workspaceId);
        }
        return orgId;
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
        return provisionWorkspace(name, ownerUserId);
    }

    /**
     * Creates the first owner's workspace during instance bootstrap, bypassing the
     * self-service-creation flag (the bootstrap actor is the trusted operator, not a
     * self-service user). Only {@code BootstrapRunner} should call this.
     */
    WorkspaceMembershipDto createWorkspaceForBootstrap(String name, int ownerUserId) {
        return provisionWorkspace(name, ownerUserId);
    }

    private WorkspaceMembershipDto provisionWorkspace(String name, int ownerUserId) {
        Workspace workspace = new Workspace();
        workspace.setOrgId(orgIdForOwner(ownerUserId, name));
        workspace.setName(name.trim());
        workspace.setSlug(generateSlug(name));
        workspaceMapper.insert(workspace);
        workspaceMapper.addMember(workspace.getId(), ownerUserId, "owner");
        return new WorkspaceMembershipDto(workspace.getId(), workspace.getName(), workspace.getSlug(), "owner");
    }

    /**
     * The organization a new workspace for {@code ownerUserId} joins: the org of a
     * workspace they already belong to (so a user's own workspaces stay in one org),
     * or a freshly created organization for a first-time owner.
     */
    private int orgIdForOwner(int ownerUserId, String name) {
        List<Workspace> existing = workspaceMapper.getWorkspacesForUser(ownerUserId);
        if (!existing.isEmpty()) {
            return getOrgId(existing.getFirst().getId());
        }
        Organization organization = new Organization();
        organization.setName(name.trim());
        organization.setSlug(generateSlug(name));
        organizationMapper.insert(organization);
        return organization.getId();
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
        if (systemActor.is(userId)) {
            return systemActor.permissions();
        }
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

    /**
     * Authorizes a mutation of a global user account: permitted only when the
     * current user is the target. Acting on another member's account is rejected —
     * workspace-scoped member management (role changes, removal) goes through the
     * member operations, which carry the owner and last-owner safeguards.
     * @param targetUserId the user the action operates on
     */
    public void requireSelf(int targetUserId) {
        if (currentUser().getId() != targetUserId) {
            throw new ForbiddenException("You can only modify your own account");
        }
    }

    /** The built-in roles and their fixed permission bundles, shown read-only in the role editor. */
    public List<WorkspaceRole> builtInRoles() {
        return List.of(
            builtInRole("owner", OWNER_PERMISSIONS),
            builtInRole("admin", ADMIN_PERMISSIONS),
            builtInRole("member", MEMBER_PERMISSIONS));
    }

    private static WorkspaceRole builtInRole(String name, Set<Permission> permissions) {
        WorkspaceRole role = new WorkspaceRole();
        role.setName(name);
        role.setPermissions(permissions.stream().map(Permission::name).sorted().toList());
        return role;
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

    /**
     * Whether the user holds any membership row in the workspace — {@code active}
     * or {@code pending}. Unlike {@link #isMember}, this does not gate on
     * acceptance, so it must never be used for auth/RBAC; it exists for surfaces
     * that address invited-but-not-yet-joined members, such as @-mention
     * references and their notifications.
     *
     * @param workspaceId the workspace to check within
     * @param userId the user to check
     * @return true if the user has an active or pending membership row
     */
    public boolean isMemberIncludingPending(int workspaceId, int userId) {
        return workspaceMapper.isMemberIncludingPending(workspaceId, userId);
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

    /** Adds a user as a PENDING member and notifies them to accept; they aren't a real member until they do. */
    public MemberDto addPendingMember(int workspaceId, User actor, User target, String role) {
        workspaceMapper.addPendingMember(workspaceId, target.getId(), role);
        notifyJoinRequest(workspaceId, target.getId(), actor);
        auditService.record("workspace.member.invite", "workspace", workspaceId, target.getDisplayName(),
                "Invited " + target.getDisplayName() + " to join", null);
        return workspaceMapper.getMember(workspaceId, target.getId());
    }

    /** Workspaces the user has been added to but not yet accepted. */
    public List<WorkspaceMembershipDto> pendingMemberships(int userId) {
        return workspaceMapper.getPendingMemberships(userId);
    }

    /** The user accepts a pending invitation, becoming an active member. */
    public WorkspaceMembershipDto approveMembership(int workspaceId, int userId) {
        if (workspaceMapper.activateMember(workspaceId, userId) == 0) {
            throw new ResourceNotFoundException("No pending invitation for this workspace");
        }
        auditService.record("workspace.member.join", "workspace", workspaceId, null, "Accepted invitation", null);
        return workspaceMapper.getMembershipsForUser(userId).stream()
            .filter(m -> m.getId() == workspaceId)
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));
    }

    /** The user declines a pending invitation; the row (and its notification) is removed. */
    public void declineMembership(int workspaceId, int userId) {
        MemberDto member = workspaceMapper.getMember(workspaceId, userId);
        if (member == null || !"pending".equals(member.getStatus())) {
            throw new ResourceNotFoundException("No pending invitation for this workspace");
        }
        workspaceMapper.removeMember(workspaceId, userId);
        auditService.record("workspace.member.decline", "workspace", workspaceId, null, "Declined invitation", null);
    }

    /** The user leaves a workspace they belong to, unassigning their tasks and clearing deal ownership. */
    public void leaveWorkspace(int workspaceId, int userId) {
        String role = workspaceMapper.getRole(workspaceId, userId);
        if (role == null) {
            throw new ResourceNotFoundException("You are not a member of this workspace");
        }
        if ("owner".equals(role) && workspaceMapper.countOwners(workspaceId) <= 1) {
            throw new BadRequestException("Transfer ownership before leaving; a workspace must keep an owner");
        }
        workspaceMapper.unassignMemberTasks(workspaceId, userId);
        workspaceMapper.clearMemberDealOwnership(workspaceId, userId);
        workspaceMapper.removeMember(workspaceId, userId);
        auditService.record("workspace.member.leave", "workspace", workspaceId, null, "Left the workspace", null);
    }

    private void notifyJoinRequest(int workspaceId, int recipientId, User actor) {
        try {
            Notification notification = new Notification();
            notification.setWorkspaceId(workspaceId);
            notification.setRecipientId(recipientId);
            notification.setType("workspace.join");
            notification.setCategory("workspace");
            notification.setSeverity("info");
            notification.setTemplateVersion(1);
            notification.setTitle("Workspace invitation");
            notification.setBody("You have a pending workspace invitation.");
            notification.setActorId(actor.getId());
            notification.setActorLabel(actor.getDisplayName());
            notification.setActionUrl("/settings/membership");
            notification.setDedupeKey("workspace.join:" + workspaceId);
            notification.setTriggeredAt(LocalDateTime.now(ZoneOffset.UTC).format(TS));
            notificationMapper.upsert(notification);
        } catch (RuntimeException e) {
            // Best-effort: the pending membership row is the source of truth.
        }
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
