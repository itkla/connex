package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
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
    private final TenantContext tenantContext;

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

    public boolean isMember(int workspaceId, int userId) {
        return workspaceMapper.isMember(workspaceId, userId);
    }

    public List<User> getMembers(int workspaceId) {
        return workspaceMapper.getMembers(workspaceId);
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
