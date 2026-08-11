package ooo.klae.connex.backend.services;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.dto.WorkspaceIdentityDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
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
    private final UserMapper userMapper;
    private final OrganizationMapper organizationMapper;
    private final OrgMemberService orgMemberService;
    private final OrgAllowedDomainService orgAllowedDomainService;
    private final RoleMapper roleMapper;
    private final NotificationMapper notificationMapper;
    private final UserOffboardingService userOffboardingService;
    private final NotificationDelivery notificationDelivery;
    private final NotificationStateVersionService notificationStateVersionService;
    private final TenantContext tenantContext;
    private final AuditService auditService;
    private final SystemActor systemActor;
    private final SessionSecurityService sessionSecurityService;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int WORKSPACE_NAME_MAX = 128;
    private static final int TIMEZONE_MAX = 64;
    private static final Set<String> AVAILABLE_TIMEZONES = Set.copyOf(ZoneId.getAvailableZoneIds());

    /** Built-in role permission bundles. Owner gets the full catalog. */
    private static final Set<Permission> MEMBER_PERMISSIONS = memberPermissions();
    private static final Set<Permission> ADMIN_PERMISSIONS = adminPermissions();
    private static final Set<Permission> OWNER_PERMISSIONS = Permission.grantableSet();

    private static EnumSet<Permission> memberPermissions() {
        return EnumSet.of(
            Permission.COMPANY_CREATE, Permission.COMPANY_UPDATE,
            Permission.PERSON_CREATE, Permission.PERSON_UPDATE, Permission.PERSON_DELETE,
            Permission.DEAL_CREATE, Permission.DEAL_UPDATE, Permission.DEAL_DELETE,
            Permission.ACTIVITY_CREATE, Permission.ACTIVITY_UPDATE, Permission.ACTIVITY_DELETE,
            Permission.NOTE_CREATE, Permission.NOTE_UPDATE, Permission.NOTE_DELETE,
            Permission.TASK_CREATE, Permission.TASK_UPDATE, Permission.TASK_DELETE,
            Permission.ATTACHMENT_CREATE, Permission.ATTACHMENT_DELETE,
            Permission.REPORT_READ, Permission.REPORT_CREATE, Permission.REPORT_UPDATE,
            Permission.REPORT_DELETE, Permission.GOAL_READ, Permission.CAMPAIGN_VIEW);
    }

    private static EnumSet<Permission> adminPermissions() {
        EnumSet<Permission> permissions = memberPermissions();
        permissions.addAll(EnumSet.of(
            Permission.COMPANY_DELETE, Permission.PIPELINE_MANAGE, Permission.TAG_MANAGE,
            Permission.PRODUCT_MANAGE, Permission.DOCUMENT_MANAGE, Permission.DOCUMENT_APPROVE,
            Permission.CUSTOM_FIELD_MANAGE, Permission.SHARE_MANAGE, Permission.MEMBER_MANAGE,
            Permission.AUDIT_READ, Permission.WORKSPACE_SETTINGS, Permission.RULE_MANAGE,
            Permission.AI_USE, Permission.AI_SESSION_SHARE, Permission.AI_SESSION_ADMIN,
            Permission.GOAL_MANAGE,
            Permission.CAMPAIGN_MANAGE,
            Permission.CAMPAIGN_SEND, Permission.CONSENT_MANAGE));
        return permissions;
    }

    @Value("${connex.workspaces.allow-self-service-creation:false}")
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

    /** The id of the authenticated user in the current security context. */
    public int getCurrentUserId() {
        return currentUser().getId();
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

    /**
     * Resolves analytics calendar boundaries from the active workspace override, falling back to
     * the authenticated actor's persisted timezone and then UTC for legacy users.
     *
     * @return server-owned IANA timezone for active-workspace analytics
     */
    public String getCurrentAnalyticsTimezone() {
        Workspace workspace = workspaceMapper.getActiveById(getCurrentWorkspaceId());
        if (workspace == null) {
            throw new ForbiddenException("Active workspace is not accessible");
        }
        User actor = userMapper.getUserById(getCurrentUserId());
        if (actor == null) {
            throw new ForbiddenException("Authentication is required to resolve a workspace");
        }
        String actorTimezone = TimezoneSupport.validateIana(actor.getTimezone(), "UTC");
        return TimezoneSupport.validateIana(workspace.getTimezone(), actorTimezone);
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

    /** Returns whether the active member holds the built-in admin or owner role without a custom role. */
    public boolean isBuiltInAdmin(int workspaceId, int userId) {
        String role = workspaceMapper.getRole(workspaceId, userId);
        return workspaceMapper.getMemberRoleId(workspaceId, userId) == null
            && ("admin".equals(role) || "owner".equals(role));
    }

    public List<WorkspaceMembershipDto> getMembershipsForCurrentUser() {
        return workspaceMapper.getMembershipsForUser(currentUser().getId());
    }

    /**
     * Replaces the workspace's mutable display identity while preserving its immutable id and slug.
     * Authorization is revalidated from locked user, workspace, organization, membership, custom-role,
     * and permission rows before the write. A null timezone clears the workspace override.
     *
     * @param workspaceId workspace to update
     * @param actorId authenticated actor
     * @param nameRaw required display name
     * @param timezoneRaw nullable IANA timezone override
     * @param expectedNameRaw display name observed before editing
     * @param expectedTimezoneRaw nullable timezone observed before editing
     * @param expectedIdentityVersion identity version observed before editing
     * @return canonical persisted workspace identity
     */
    @Transactional
    public WorkspaceIdentityDto updateIdentity(
            int workspaceId,
            int actorId,
            String nameRaw,
            String timezoneRaw,
            String expectedNameRaw,
            String expectedTimezoneRaw,
            long expectedIdentityVersion) {
        requirePermission(workspaceId, actorId, Permission.WORKSPACE_SETTINGS);
        sessionSecurityService.requireRecentAuthentication(actorId);
        String name = normalizeWorkspaceName(nameRaw);
        String timezone = normalizeWorkspaceTimezone(timezoneRaw);
        String expectedName = normalizeWorkspaceName(expectedNameRaw);
        String expectedTimezone = normalizeWorkspaceTimezone(expectedTimezoneRaw);
        Workspace before = lockWorkspaceIdentityMutation(workspaceId, actorId);
        if (before.getIdentityVersion() != expectedIdentityVersion
                || !Objects.equals(before.getName(), expectedName)
                || !Objects.equals(before.getTimezone(), expectedTimezone)) {
            throw new ConflictException("Workspace settings changed; refresh and retry");
        }
        boolean nameChanged = !Objects.equals(before.getName(), name);
        boolean timezoneChanged = !Objects.equals(before.getTimezone(), timezone);
        if (nameChanged || timezoneChanged) {
            if (workspaceMapper.updateIdentity(workspaceId, name, timezone) == 0) {
                throw new ForbiddenException("Requires the WORKSPACE_SETTINGS permission in this workspace");
            }
            if (nameChanged) {
                auditService.recordScoped(
                    "workspace.rename",
                    "workspace",
                    workspaceId,
                    workspaceId,
                    before.getOrgId(),
                    name,
                    "Renamed workspace",
                    auditService.singleChange("name", before.getName(), name));
            }
            if (timezoneChanged) {
                auditService.recordScoped(
                    "workspace.settings.update",
                    "workspace",
                    workspaceId,
                    workspaceId,
                    before.getOrgId(),
                    name,
                    "Updated workspace timezone",
                    auditService.singleChange("timezone", before.getTimezone(), timezone));
            }
        }
        Workspace updated = workspaceMapper.getActiveById(workspaceId);
        if (updated == null) {
            throw new ForbiddenException("Requires the WORKSPACE_SETTINGS permission in this workspace");
        }
        return workspaceIdentity(updated);
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

    @Transactional
    WorkspaceMembershipDto provisionWorkspace(String name, int ownerUserId) {
        if (userMapper.lockByIdForShare(ownerUserId) == null) {
            throw new ResourceNotFoundException("User not found: " + ownerUserId);
        }
        int orgId = orgIdForOwner(ownerUserId, name);
        if (organizationMapper.lockActiveByIdForShare(orgId) == null) {
            throw new ForbiddenException("Organization teardown is in progress");
        }
        Workspace workspace = new Workspace();
        workspace.setOrgId(orgId);
        workspace.setName(name.trim());
        workspace.setSlug(generateSlug(name));
        workspaceMapper.insert(workspace);
        workspaceMapper.addMember(workspace.getId(), ownerUserId, "owner");
        notificationStateVersionService.markChanged(ownerUserId);
        auditService.record("org.workspace.create", "organization", orgId, workspace.getName(),
                "Workspace created", Map.of("workspaceId", workspace.getId(), "ownerUserId", ownerUserId));
        WorkspaceMembershipDto membership =
                new WorkspaceMembershipDto(workspace.getId(), workspace.getName(), workspace.getSlug(), "owner");
        Organization organization = organizationMapper.getById(orgId);
        if (organization == null) {
            throw new IllegalStateException("Provisioned organization disappeared");
        }
        membership.setOrgId(orgId);
        membership.setIdentityVersion(workspace.getIdentityVersion());
        membership.setOrgName(organization.getName());
        membership.setOrgIdentityVersion(organization.getIdentityVersion());
        membership.setOrgRole(orgMemberService.orgRoleOf(orgId, ownerUserId));
        return membership;
    }

    /**
     * The organization a new workspace for {@code ownerUserId} joins. The active
     * workspace's organization is reused only when the creator holds
     * {@link Permission#WORKSPACE_SETTINGS} there (built-in owner/admin, or a
     * custom role granting it) — the org is the customer boundary, and expanding
     * it is an administrative act. In every other case (registration, bootstrap,
     * or a creator who is merely a member of the active workspace, e.g. an external
     * collaborator inside a client's org) the workspace gets a freshly created
     * organization, so a guest membership can never pull a personal workspace into
     * the host organization.
     */
    private int orgIdForOwner(int ownerUserId, String name) {
        Integer activeOrgId = activeOrgIdIfAdministrator(ownerUserId);
        if (activeOrgId != null) {
            return activeOrgId;
        }
        Organization organization = new Organization();
        organization.setName(name.trim());
        organization.setSlug(generateSlug(name));
        organizationMapper.insert(organization);
        orgMemberService.addFoundingOwner(organization.getId(), ownerUserId);
        auditService.record("org.create", "organization", organization.getId(), organization.getName(),
                "Organization created", Map.of("ownerUserId", ownerUserId));
        auditService.record("org.member.founding_owner", "organization", organization.getId(), organization.getName(),
                "Founding organization owner granted", Map.of("userId", ownerUserId));
        return organization.getId();
    }

    /**
     * The active workspace's organization when the resolved tenant context belongs
     * to {@code creatorUserId} and their effective permissions there include
     * {@link Permission#WORKSPACE_SETTINGS}, or null when no such administrative
     * context applies.
     */
    private Integer activeOrgIdIfAdministrator(int creatorUserId) {
        if (!tenantContext.isResolved()) {
            return null;
        }
        Integer contextUserId = tenantContext.getUserId();
        if (contextUserId == null || contextUserId != creatorUserId) {
            return null;
        }
        Integer workspaceId = tenantContext.getWorkspaceId();
        if (workspaceId == null
                || !permissionsFor(workspaceId, creatorUserId).contains(Permission.WORKSPACE_SETTINGS)) {
            return null;
        }
        return tenantContext.getOrgId();
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

    /**
     * Verifies the user is an active member of the workspace while holding a {@code FOR UPDATE} lock on
     * their membership row until the surrounding transaction commits. Unlike {@link #requireMember}, this
     * closes the time-of-check/time-of-use gap against concurrent offboarding: a caller must invoke it
     * inside its own transaction (e.g. {@code updateOwner}) so the assignment and the membership check
     * cannot straddle {@code UserOffboardingService}'s membership lock, preventing a dangling owner id
     * pointing at a just-removed member.
     */
    public void lockAndRequireMember(int workspaceId, int userId) {
        if (workspaceMapper.lockActiveMembership(workspaceId, userId) == null) {
            throw new ForbiddenException("User " + userId + " is not a member of this workspace");
        }
    }

    /** Locks every requested active membership in ascending user-id order. */
    public void lockAndRequireMembers(int workspaceId, List<Integer> userIds) {
        userIds.stream().distinct().sorted()
            .forEach(userId -> lockAndRequireMember(workspaceId, userId));
    }

    /** Returns whether every requested id is an active member of the workspace. */
    public boolean areActiveMembers(int workspaceId, List<Integer> memberIds) {
        return workspaceMapper.countActiveMembers(workspaceId, memberIds) == memberIds.size();
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
        if (userMapper.isAccountDeletionReserved(userId)) {
            return EnumSet.noneOf(Permission.class);
        }
        Integer roleId = workspaceMapper.getMemberRoleId(workspaceId, userId);
        if (roleId != null) {
            return parsePermissions(roleMapper.findPermissions(workspaceId, roleId));
        }
        Role role = Role.of(workspaceMapper.getRole(workspaceId, userId));
        if (role == null) {
            return EnumSet.noneOf(Permission.class);
        }
        return builtInPermissions(role);
    }

    /** Returns the current member's effective permissions in the active workspace. */
    public Set<Permission> getCurrentPermissions() {
        return permissionsFor(getCurrentWorkspaceId(), currentUser().getId());
    }

    /** The fixed permission bundle backing a built-in role. */
    private static Set<Permission> builtInPermissions(Role role) {
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

    /**
     * Refuses when the user is the only active owner of any workspace — deleting the account would
     * leave that workspace ownerless (workspace_member is {@code ON DELETE CASCADE}, bypassing the
     * last-owner safeguards on the member operations). Owned workspace roots are locked in id
     * order to serialize owner-sensitive operations, then all of the user's membership rows are
     * locked in workspace order before the owner rows are read. This matches notification mark-all
     * ordering while preserving the concurrent co-owner deletion guard; must run in a transaction.
     * They must transfer ownership first.
     */
    public void assertNotSoleOwnerOfAnyWorkspace(int userId) {
        List<Integer> ownedWorkspaceIds = lockOwnedWorkspaceRoots(userId);
        notificationMapper.lockRecipientMemberships(userId);
        assertNotSoleOwnerOfWorkspaces(ownedWorkspaceIds);
    }

    List<Integer> lockOwnedWorkspaceRoots(int userId) {
        List<Integer> ownedWorkspaceIds = discoverOwnedWorkspaceIds(userId);
        lockAccountWorkspaceRoots(ownedWorkspaceIds, List.of());
        return List.copyOf(ownedWorkspaceIds);
    }

    List<Integer> discoverOwnedWorkspaceIds(int userId) {
        return List.copyOf(workspaceMapper.workspaceIdsOwnedBy(userId));
    }

    void lockAccountWorkspaceRoots(
            List<Integer> ownedWorkspaceIds, List<Integer> sharedWorkspaceIds) {
        Set<Integer> owned = new HashSet<>(ownedWorkspaceIds);
        TreeSet<Integer> ordered = new TreeSet<>(ownedWorkspaceIds);
        ordered.addAll(sharedWorkspaceIds);
        for (int workspaceId : ordered) {
            if (owned.contains(workspaceId)) {
                workspaceMapper.lockWorkspace(workspaceId);
            } else {
                workspaceMapper.lockWorkspaceForShare(workspaceId);
            }
        }
    }

    private void lockWorkspaceMutationRoot(int workspaceId) {
        if (workspaceMapper.lockWorkspace(workspaceId) == null) {
            throw new ResourceNotFoundException("Workspace not found: " + workspaceId);
        }
    }

    private Workspace lockWorkspaceIdentityMutation(int workspaceId, int actorId) {
        if (userMapper.lockById(actorId) == null
                || userMapper.isAccountDeletionReserved(actorId)) {
            throw workspaceSettingsForbidden();
        }
        Workspace workspace = workspaceMapper.lockActiveIdentity(workspaceId);
        if (workspace == null) {
            throw workspaceSettingsForbidden();
        }
        if (organizationMapper.lockActiveByIdForShare(workspace.getOrgId()) == null) {
            throw workspaceSettingsForbidden();
        }
        WorkspaceMember membership = workspaceMapper.lockAuthorizationMembership(workspaceId, actorId);
        if (!isExactMembership(membership, workspaceId, actorId)
                || !"active".equals(membership.getStatus())) {
            throw workspaceSettingsForbidden();
        }
        Set<Permission> permissions;
        if (membership.getRoleId() == null) {
            Role role = Role.of(membership.getRole());
            if (role == null) {
                throw workspaceSettingsForbidden();
            }
            permissions = builtInPermissions(role);
        } else {
            int roleId = membership.getRoleId();
            if (roleMapper.lockRole(workspaceId, roleId) == null) {
                throw workspaceSettingsForbidden();
            }
            permissions = parsePermissions(roleMapper.lockPermissions(workspaceId, roleId));
        }
        if (!permissions.contains(Permission.WORKSPACE_SETTINGS)) {
            throw workspaceSettingsForbidden();
        }
        return workspace;
    }

    private static String normalizeWorkspaceName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Workspace name is required");
        }
        String name = raw.trim();
        if (name.length() > WORKSPACE_NAME_MAX) {
            throw new BadRequestException("Workspace name must be 128 characters or fewer");
        }
        return name;
    }

    private static String normalizeWorkspaceTimezone(String raw) {
        if (raw == null) {
            return null;
        }
        String timezone = raw.trim();
        if (timezone.isEmpty()) {
            throw new BadRequestException("Workspace timezone must be null or a valid IANA timezone");
        }
        if (timezone.length() > TIMEZONE_MAX) {
            throw new BadRequestException("Workspace timezone must be 64 characters or fewer");
        }
        try {
            String canonical = ZoneId.of(timezone).getId();
            if (!AVAILABLE_TIMEZONES.contains(canonical)) {
                throw new BadRequestException("Workspace timezone must be a valid IANA timezone");
            }
            return canonical;
        } catch (DateTimeException exception) {
            throw new BadRequestException("Workspace timezone must be a valid IANA timezone");
        }
    }

    private static WorkspaceIdentityDto workspaceIdentity(Workspace workspace) {
        return new WorkspaceIdentityDto(
            workspace.getId(),
            workspace.getOrgId(),
            workspace.getName(),
            workspace.getSlug(),
            workspace.getTimezone(),
            workspace.getIdentityVersion(),
            workspace.getUpdatedAt());
    }

    private static ForbiddenException workspaceSettingsForbidden() {
        return new ForbiddenException("Requires the WORKSPACE_SETTINGS permission in this workspace");
    }

    void assertNotSoleOwnerOfWorkspaces(List<Integer> ownedWorkspaceIds) {
        for (int workspaceId : ownedWorkspaceIds) {
            if (workspaceMapper.lockOwnerIds(workspaceId).size() <= 1) {
                throw new BadRequestException("Transfer workspace ownership before deleting your account");
            }
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
                Permission permission = Permission.valueOf(value);
                if (Permission.isGrantable(permission)) {
                    permissions.add(permission);
                }
            } catch (IllegalArgumentException | NullPointerException ignored) {
            }
        }
        return permissions;
    }

    /**
     * Ensures the actor may confer every permission in {@code requested}: a member
     * can only grant permissions they themselves hold in the workspace. This caps
     * ROLE_MANAGE so a delegate cannot mint or assign a role broader than their own
     * authority (privilege escalation). Owners hold the full catalog and are
     * unaffected.
     *
     * @param workspaceId the workspace the grant applies to
     * @param actorId the member performing the grant
     * @param requested the permissions being conferred
     */
    public void requireGrantable(int workspaceId, int actorId, Set<Permission> requested) {
        requireGrantable(permissionsFor(workspaceId, actorId), requested);
    }

    private static void requireGrantable(Set<Permission> held, Set<Permission> requested) {
        for (Permission permission : requested) {
            if (!held.contains(permission)) {
                throw new ForbiddenException("You cannot grant the " + permission
                        + " permission because you do not hold it");
            }
        }
    }

    /** Assigns a custom role to a member; managing roles requires the ROLE_MANAGE permission. */
    @Transactional
    public MemberDto assignCustomRole(int workspaceId, int actorId, int targetUserId, int roleId) {
        requirePermission(workspaceId, actorId, Permission.ROLE_MANAGE);
        sessionSecurityService.requireRecentAuthentication(actorId);
        LockedRoleMutation locks = lockRoleMutation(
            workspaceId,
            actorId,
            targetUserId,
            Permission.ROLE_MANAGE,
            roleId,
            "Role not found in this workspace");
        requireGrantable(locks.actorPermissions(), locks.permissionsForRole(roleId));
        MemberDto target = getLockedRoleMutationTarget(workspaceId, targetUserId);
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
     * ownership, and the last owner cannot be demoted. The actor may only assign a
     * built-in role whose entire permission bundle they themselves hold, so a
     * delegate holding {@code MEMBER_MANAGE} alone cannot promote anyone (including
     * themselves) to a role that confers permissions they lack.
     */
    @Transactional
    public MemberDto changeMemberRole(int workspaceId, int actorId, int targetUserId, String roleRaw) {
        requirePermission(workspaceId, actorId, Permission.MEMBER_MANAGE);
        sessionSecurityService.requireRecentAuthentication(actorId);
        Role newRole = parseAssignableRole(roleRaw);
        LockedRoleMutation locks = lockRoleMutation(
            workspaceId,
            actorId,
            targetUserId,
            Permission.MEMBER_MANAGE,
            null,
            null);
        WorkspaceMember actorMembership = locks.actorMembership();
        WorkspaceMember targetMembership = locks.targetMembership();
        if (newRole == Role.OWNER) {
            requireLockedRole(actorMembership, Role.OWNER);
        }
        if ("owner".equals(targetMembership.getRole()) && newRole != Role.OWNER) {
            requireLockedRole(actorMembership, Role.OWNER);
            if ("active".equals(targetMembership.getStatus())
                    && workspaceMapper.lockOwnerIds(workspaceId).size() <= 1) {
                throw new BadRequestException("A workspace must keep at least one owner");
            }
        }
        requireGrantable(locks.actorPermissions(), builtInPermissions(newRole));
        MemberDto target = getLockedRoleMutationTarget(workspaceId, targetUserId);
        workspaceMapper.updateMemberRole(workspaceId, targetUserId, newRole.name().toLowerCase());
        auditService.record("workspace.member.role", "workspace", workspaceId, target.getDisplayName(),
                "Changed " + target.getDisplayName() + " to " + newRole.name().toLowerCase(), null);
        return workspaceMapper.getMember(workspaceId, targetUserId);
    }

    /** Locks current authorization and the exact custom-role root before deletion. */
    public void lockRoleDeletionAuthorization(int workspaceId, int actorId, int roleId) {
        lockRoleMutationAuthorization(workspaceId, actorId, roleId, Set.of());
    }

    void lockRoleMutationAuthorization(
            int workspaceId,
            int actorId,
            Integer roleId,
            Set<Permission> requestedPermissions) {
        LockedRoleMutation locks = lockRoleMutation(
            workspaceId,
            actorId,
            null,
            Permission.ROLE_MANAGE,
            roleId,
            "Role not found");
        requireGrantable(locks.actorPermissions(), requestedPermissions);
    }

    private LockedRoleMutation lockRoleMutation(
            int workspaceId,
            int actorId,
            Integer targetUserId,
            Permission requiredPermission,
            Integer requestedRoleId,
            String missingRequestedRoleMessage) {
        TreeSet<Integer> userIds = new TreeSet<>();
        userIds.add(actorId);
        if (targetUserId != null) {
            userIds.add(targetUserId);
        }
        for (int userId : userIds) {
            if (userMapper.lockById(userId) == null) {
                if (userId == actorId) {
                    throw new ForbiddenException(
                        "Requires the " + requiredPermission + " permission in this workspace");
                }
                throw roleMutationTargetNotFound();
            }
        }

        lockWorkspaceMutationRoot(workspaceId);
        Map<Integer, WorkspaceMember> memberships = new LinkedHashMap<>();
        for (int userId : userIds) {
            WorkspaceMember membership = workspaceMapper.lockAuthorizationMembership(workspaceId, userId);
            memberships.put(userId, membership);
        }

        WorkspaceMember actorMembership = memberships.get(actorId);
        if (!isExactMembership(actorMembership, workspaceId, actorId)
                || !"active".equals(actorMembership.getStatus())) {
            throw new ForbiddenException(
                "Requires the " + requiredPermission + " permission in this workspace");
        }
        WorkspaceMember targetMembership = targetUserId == null
            ? null
            : memberships.get(targetUserId);
        if (targetUserId != null
                && (!isExactMembership(targetMembership, workspaceId, targetUserId)
                    || !("active".equals(targetMembership.getStatus())
                        || "pending".equals(targetMembership.getStatus())))) {
            throw roleMutationTargetNotFound();
        }

        TreeSet<Integer> roleIds = new TreeSet<>();
        if (actorMembership.getRoleId() != null) {
            roleIds.add(actorMembership.getRoleId());
        }
        if (requestedRoleId != null) {
            roleIds.add(requestedRoleId);
        }
        for (int roleId : roleIds) {
            if (roleMapper.lockRole(workspaceId, roleId) == null) {
                if (requestedRoleId != null && roleId == requestedRoleId) {
                    throw new ResourceNotFoundException(missingRequestedRoleMessage);
                }
                throw new ForbiddenException(
                    "Requires the " + requiredPermission + " permission in this workspace");
            }
        }

        Map<Integer, Set<Permission>> rolePermissions = new LinkedHashMap<>();
        for (int roleId : roleIds) {
            rolePermissions.put(
                roleId,
                parsePermissions(roleMapper.lockPermissions(workspaceId, roleId)));
        }
        Set<Permission> actorPermissions;
        if (actorMembership.getRoleId() == null) {
            Role actorRole = Role.of(actorMembership.getRole());
            if (actorRole == null) {
                throw new ForbiddenException(
                    "Requires the " + requiredPermission + " permission in this workspace");
            }
            actorPermissions = builtInPermissions(actorRole);
        } else {
            actorPermissions = rolePermissions.get(actorMembership.getRoleId());
        }
        if (actorPermissions == null || !actorPermissions.contains(requiredPermission)) {
            throw new ForbiddenException(
                "Requires the " + requiredPermission + " permission in this workspace");
        }
        return new LockedRoleMutation(
            actorMembership,
            targetMembership,
            Set.copyOf(actorPermissions),
            Map.copyOf(rolePermissions));
    }

    private static boolean isExactMembership(
            WorkspaceMember membership, int workspaceId, int userId) {
        return membership != null
            && membership.getWorkspaceId() == workspaceId
            && membership.getUserId() == userId;
    }

    private static void requireLockedRole(WorkspaceMember membership, Role minimum) {
        Role actual = Role.of(membership.getRole());
        if (actual == null || actual.ordinal() < minimum.ordinal()) {
            throw new ForbiddenException("Requires " + minimum + " role in this workspace");
        }
    }

    private static ResourceNotFoundException roleMutationTargetNotFound() {
        return new ResourceNotFoundException("User is not a member of this workspace");
    }

    private MemberDto getLockedRoleMutationTarget(int workspaceId, int targetUserId) {
        MemberDto target = workspaceMapper.getMember(workspaceId, targetUserId);
        if (target == null) {
            throw new ResourceNotFoundException("User is not a member of this workspace");
        }
        return target;
    }

    /**
     * Removes a member, unassigning their tasks and clearing their deal ownership
     * first so authored history survives. Only an owner may remove an owner, and
     * never the last one.
     */
    @Transactional
    public void removeMember(int workspaceId, int actorId, int targetUserId) {
        requirePermission(workspaceId, actorId, Permission.MEMBER_MANAGE);
        sessionSecurityService.requireRecentAuthentication(actorId);
        MemberDto target = workspaceMapper.getMember(workspaceId, targetUserId);
        if (target == null) {
            throw new ResourceNotFoundException("User is not a member of this workspace");
        }
        if ("owner".equals(target.getRole())) {
            requireRole(workspaceId, actorId, Role.OWNER);
            lockOwnedWorkspaceRoots(targetUserId);
            notificationMapper.lockRecipientMemberships(targetUserId);
            if (workspaceMapper.lockOwnerIds(workspaceId).size() <= 1) {
                throw new BadRequestException("A workspace must keep at least one owner");
            }
        }
        userOffboardingService.detachMemberContent(workspaceId, targetUserId);
        workspaceMapper.removeMember(workspaceId, targetUserId);
        notificationStateVersionService.markChanged(targetUserId);
        auditService.record("workspace.member.remove", "workspace", workspaceId, target.getDisplayName(),
                "Removed " + target.getDisplayName() + " from the workspace", null);
    }

    /**
     * Adds a user as a PENDING member and notifies them to accept; they aren't a
     * real member until they do. Any notification rows left over from an earlier
     * membership are cleaned first — with the cross-plane cascades gone (#440
     * increment 3) a row inserted while a removal was committing could otherwise
     * resurface in the re-invited member's inbox.
     */
    @Transactional
    public MemberDto addPendingMember(int workspaceId, User actor, User target, String role) {
        userOffboardingService.prepareFreshMembership(workspaceId, target.getId());
        workspaceMapper.addPendingMember(workspaceId, target.getId(), role);
        notificationStateVersionService.markChanged(target.getId());
        notifyJoinRequest(workspaceId, target.getId(), actor);
        auditService.record("workspace.member.invite", "workspace", workspaceId, target.getDisplayName(),
                "Invited " + target.getDisplayName() + " to join", null);
        return workspaceMapper.getMember(workspaceId, target.getId());
    }

    /**
     * Ensures {@code userId} is an active member of {@code workspaceId} with {@code role},
     * adding an active membership when they are not already one. Idempotent: an existing
     * member is left untouched (their current role is not changed). Used by JIT SSO
     * provisioning to place a freshly federated user into their organization's workspace.
     * @param workspaceId the workspace to join
     * @param userId the user to add
     * @param role the role to grant on a fresh join
     */
    @Transactional
    public void ensureActiveMember(int workspaceId, int userId, String role) {
        int orgId = getOrgId(workspaceId);
        if (isMember(workspaceId, userId)) {
            return;
        }
        userOffboardingService.prepareFreshMembership(workspaceId, userId);
        workspaceMapper.addMember(workspaceId, userId, role);
        notificationStateVersionService.markChanged(userId);
        auditService.record("org.workspace_member.sso_provision", "organization", orgId, null,
                "Provisioned an SSO member into a workspace",
                Map.of("workspaceId", workspaceId, "userId", userId, "role", role));
    }

    /** Workspaces the user has been added to but not yet accepted. */
    public List<WorkspaceMembershipDto> pendingMemberships(int userId) {
        return workspaceMapper.getPendingMemberships(userId);
    }

    /**
     * The user accepts a pending invitation, becoming an active member. The organization's
     * email-domain ceiling (#316) is re-applied at activation, so a pending row that predates a
     * later-tightened org policy cannot slip an out-of-policy member into the workspace.
     */
    @Transactional
    public WorkspaceMembershipDto approveMembership(int workspaceId, int userId) {
        if (userMapper.lockById(userId) == null
                || workspaceMapper.lockWorkspace(workspaceId) == null) {
            throw pendingMembershipNotFound();
        }
        WorkspaceMember membership = workspaceMapper.lockAuthorizationMembership(workspaceId, userId);
        if (!isExactMembership(membership, workspaceId, userId)
                || !"pending".equals(membership.getStatus())) {
            throw pendingMembershipNotFound();
        }
        MemberDto pending = workspaceMapper.getMember(workspaceId, userId);
        if (pending == null) {
            throw pendingMembershipNotFound();
        }
        if (!orgAllowedDomainService.isJoinAllowed(getOrgId(workspaceId), pending.getEmail())) {
            throw new ForbiddenException("This organization only allows members from approved email domains");
        }
        if (workspaceMapper.activateMember(workspaceId, userId) == 0) {
            throw pendingMembershipNotFound();
        }
        notificationStateVersionService.markChanged(userId);
        auditService.record("workspace.member.join", "workspace", workspaceId, null, "Accepted invitation", null);
        return workspaceMapper.getMembershipsForUser(userId).stream()
            .filter(m -> m.getId() == workspaceId)
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));
    }

    private static ResourceNotFoundException pendingMembershipNotFound() {
        return new ResourceNotFoundException("No pending invitation for this workspace");
    }

    /** The user declines a pending invitation; the row and its notifications are removed. */
    @Transactional
    public void declineMembership(int workspaceId, int userId) {
        MemberDto member = workspaceMapper.getMember(workspaceId, userId);
        if (member == null || !"pending".equals(member.getStatus())) {
            throw new ResourceNotFoundException("No pending invitation for this workspace");
        }
        notificationMapper.lockRecipientMemberships(userId);
        notificationMapper.deleteHistoricalNotificationBaselinesForRecipient(
            workspaceId, userId);
        notificationMapper.deleteAllForRecipient(workspaceId, userId);
        workspaceMapper.removeMember(workspaceId, userId);
        notificationStateVersionService.markChanged(userId);
        auditService.record("workspace.member.decline", "workspace", workspaceId, null, "Declined invitation", null);
    }

    /** The user leaves a workspace they belong to, unassigning their tasks and clearing deal ownership. */
    @Transactional
    public void leaveWorkspace(int workspaceId, int userId) {
        String role = workspaceMapper.getRole(workspaceId, userId);
        if (role == null) {
            throw new ResourceNotFoundException("You are not a member of this workspace");
        }
        if ("owner".equals(role)) {
            lockOwnedWorkspaceRoots(userId);
            notificationMapper.lockRecipientMemberships(userId);
            if (workspaceMapper.lockOwnerIds(workspaceId).size() <= 1) {
                throw new BadRequestException("Transfer ownership before leaving; a workspace must keep an owner");
            }
        }
        userOffboardingService.detachMemberContent(workspaceId, userId);
        workspaceMapper.removeMember(workspaceId, userId);
        notificationStateVersionService.markChanged(userId);
        auditService.record("workspace.member.leave", "workspace", workspaceId, null, "Left the workspace", null);
    }

    /**
     * Leaves a workspace and atomically persists the caller's next active workspace.
     * @param workspaceId the workspace being left
     * @param userId the departing member
     * @return the next active workspace id, or null when no membership remains
     */
    @Transactional
    public Integer leaveWorkspaceAndSelectNext(int workspaceId, int userId) {
        leaveWorkspace(workspaceId, userId);
        Integer nextWorkspaceId = defaultWorkspaceIdFor(userId);
        if (nextWorkspaceId != null) {
            rememberActive(userId, nextWorkspaceId);
        }
        return nextWorkspaceId;
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
            notificationDelivery.deliver(notification);
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

    private record LockedRoleMutation(
        WorkspaceMember actorMembership,
        WorkspaceMember targetMembership,
        Set<Permission> actorPermissions,
        Map<Integer, Set<Permission>> rolePermissions) {

        private Set<Permission> permissionsForRole(int roleId) {
            Set<Permission> permissions = rolePermissions.get(roleId);
            return permissions == null ? Set.of() : permissions;
        }
    }
}
