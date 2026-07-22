package ooo.klae.connex.backend.services;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.Permission;

/** Acquires and validates the shared principal and authorization locks for workflow mutations. */
@Service
@RequiredArgsConstructor
public class WorkflowPrincipalLockService {

    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final RoleMapper roleMapper;

    /** Locks principals and current authorization for a user-mode lifecycle mutation. */
    public LockedPrincipals lockUserMutation(
            int workspaceId,
            int actorId,
            Collection<Integer> discoveredPrincipalIds,
            Collection<Integer> requiredActivePrincipalIds) {
        LockedAuthorization authorization = lockSharedRoots(
            workspaceId, actorId, discoveredPrincipalIds, requiredActivePrincipalIds);
        Set<Permission> actorPermissions = lockCurrentPermissions(
            workspaceId, authorization.actorMembership());
        return authorization.principals().withActorPermissions(actorPermissions);
    }

    /** Locks principals and requires a current built-in administrator for a system-mode mutation. */
    public LockedPrincipals lockSystemMutation(
            int workspaceId,
            int actorId,
            Collection<Integer> discoveredPrincipalIds) {
        LockedAuthorization authorization = lockSharedRoots(
            workspaceId, actorId, discoveredPrincipalIds, Set.of());
        WorkspaceMember actor = authorization.actorMembership();
        if (actor.getRoleId() != null
                || !("admin".equals(actor.getRole()) || "owner".equals(actor.getRole()))) {
            throw new ForbiddenException("Requires a built-in admin role in this workspace");
        }
        return authorization.principals().withActorPermissions(Permission.grantableSet());
    }

    private LockedAuthorization lockSharedRoots(
            int workspaceId,
            int actorId,
            Collection<Integer> discoveredPrincipalIds,
            Collection<Integer> requiredActivePrincipalIds) {
        TreeSet<Integer> requestedIds = sortedIds(discoveredPrincipalIds);
        requestedIds.add(actorId);
        TreeSet<Integer> activeIds = sortedIds(requiredActivePrincipalIds);
        activeIds.add(actorId);
        requestedIds.addAll(activeIds);

        Set<Integer> existingIds = new LinkedHashSet<>();
        for (int userId : requestedIds) {
            if (userMapper.lockById(userId) != null) {
                existingIds.add(userId);
            }
        }
        if (!existingIds.contains(actorId)) {
            throw new ForbiddenException("Workflow actor account no longer exists");
        }
        if (workspaceMapper.lockWorkspaceForShare(workspaceId) == null) {
            throw new ResourceNotFoundException("Workspace not found: " + workspaceId);
        }

        WorkspaceMember actorMembership = null;
        for (int userId : activeIds) {
            if (!existingIds.contains(userId)) {
                throw inactivePrincipal(userId, actorId);
            }
            WorkspaceMember membership = workspaceMapper.lockAuthorizationMembership(workspaceId, userId);
            if (membership == null || !"active".equals(membership.getStatus())) {
                throw inactivePrincipal(userId, actorId);
            }
            if (userId == actorId) {
                actorMembership = membership;
            }
        }
        if (actorMembership == null) {
            throw new ForbiddenException("Workflow actor is not an active workspace member");
        }
        LockedPrincipals principals = new LockedPrincipals(requestedIds, existingIds);
        return new LockedAuthorization(principals, actorMembership);
    }

    private Set<Permission> lockCurrentPermissions(int workspaceId, WorkspaceMember actor) {
        Integer roleId = actor.getRoleId();
        if (roleId == null) {
            if ("admin".equals(actor.getRole()) || "owner".equals(actor.getRole())) {
                return Permission.grantableSet();
            }
            throw new ForbiddenException("Requires the RULE_MANAGE permission in this workspace");
        }
        if (roleMapper.lockRole(workspaceId, roleId) == null) {
            throw new ForbiddenException("Requires the RULE_MANAGE permission in this workspace");
        }
        EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);
        Collection<String> lockedPermissions = roleMapper.lockPermissions(workspaceId, roleId);
        if (lockedPermissions == null) {
            throw new ForbiddenException("Requires the RULE_MANAGE permission in this workspace");
        }
        for (String value : lockedPermissions) {
            if (value == null) {
                throw new ForbiddenException(
                    "Requires the RULE_MANAGE permission in this workspace");
            }
            try {
                permissions.add(Permission.valueOf(value));
            } catch (IllegalArgumentException exception) {
                throw new ForbiddenException(
                    "Requires the RULE_MANAGE permission in this workspace");
            }
        }
        if (!permissions.contains(Permission.RULE_MANAGE)) {
            throw new ForbiddenException("Requires the RULE_MANAGE permission in this workspace");
        }
        return Set.copyOf(permissions);
    }

    private static TreeSet<Integer> sortedIds(Collection<Integer> ids) {
        TreeSet<Integer> sorted = new TreeSet<>();
        if (ids != null) {
            ids.stream().filter(Objects::nonNull).forEach(sorted::add);
        }
        return sorted;
    }

    private static RuntimeException inactivePrincipal(int userId, int actorId) {
        if (userId == actorId) {
            return new ForbiddenException("Workflow actor is not an active workspace member");
        }
        return new ConflictException("Workflow run-as user is not an active workspace member");
    }

    /** The exact user roots requested and the subset that still existed when locked. */
    public record LockedPrincipals(
            Set<Integer> requestedIds,
            Set<Integer> existingIds,
            Set<Permission> actorPermissions) {
        LockedPrincipals(Set<Integer> requestedIds, Set<Integer> existingIds) {
            this(requestedIds, existingIds, Permission.grantableSet());
        }

        public LockedPrincipals {
            requestedIds = Collections.unmodifiableSet(new LinkedHashSet<>(requestedIds));
            existingIds = Collections.unmodifiableSet(new LinkedHashSet<>(existingIds));
            actorPermissions = actorPermissions.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(actorPermissions));
        }

        private LockedPrincipals withActorPermissions(Set<Permission> permissions) {
            return new LockedPrincipals(requestedIds, existingIds, permissions);
        }

        /** Fails when a current persisted reference was not discovered and locked as an existing root. */
        public void requireCurrentReferences(Collection<Integer> currentPrincipalIds) {
            for (Integer userId : sortedIds(currentPrincipalIds)) {
                if (!requestedIds.contains(userId) || !existingIds.contains(userId)) {
                    throw new ConflictException("Workflow principal state changed during authorization");
                }
            }
        }

        /** Fails when a current reference was not part of the non-locking discovery. */
        public void requireDiscoveredReferences(Collection<Integer> currentPrincipalIds) {
            for (Integer userId : sortedIds(currentPrincipalIds)) {
                if (!requestedIds.contains(userId)) {
                    throw new ConflictException("Workflow principal state changed during authorization");
                }
            }
        }

        /** Fails closed when a required persisted identity was erased. */
        public void requireExisting(Integer userId, String message) {
            if (userId == null || !existingIds.contains(userId)) {
                throw new ConflictException(message);
            }
        }

        /** Fails against the current permission rows locked with the actor's role. */
        public void requirePermissions(Collection<Permission> requiredPermissions) {
            Collection<Permission> required = Objects.requireNonNull(requiredPermissions);
            EnumSet<Permission> sorted = required.isEmpty()
                ? EnumSet.noneOf(Permission.class)
                : EnumSet.copyOf(required);
            for (Permission permission : sorted) {
                if (!actorPermissions.contains(permission)) {
                    throw new ForbiddenException(
                        "Requires the " + permission + " permission in this workspace");
                }
            }
        }
    }

    private record LockedAuthorization(
        LockedPrincipals principals,
        WorkspaceMember actorMembership) { }
}
