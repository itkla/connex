package ooo.klae.connex.backend.services;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.WorkspaceService.LockedPermissionSnapshot;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Serializes candidate-affecting person, company, and deal mutations across one organization.
 */
@Service
@RequiredArgsConstructor
public class DuplicateDecisionLockService {

    private final WorkspaceService workspaceService;
    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final OrganizationMapper organizationMapper;

    /**
     * Locks the active actor, workspace, membership, and organization in lifecycle order.
     *
     * @return locked organization id
     */
    public int lockCurrentOrganization() {
        return lockCurrentOrganization(null, false);
    }

    /** Locks current permission authority before entering the organization's duplicate mutex. */
    public LockedOrganization lockCurrentOrganization(Permission requiredPermission) {
        return lockCurrentOrganization(null, false, requiredPermission);
    }

    /**
     * Locks the current organization while retaining an additional active workspace root.
     *
     * @param additionalWorkspaceId workspace whose lifecycle must remain active
     * @return locked organization id
     */
    public int lockCurrentOrganizationWithWorkspace(int additionalWorkspaceId) {
        return lockCurrentOrganization(additionalWorkspaceId, false);
    }

    /** Retains the target workspace and current permission authority before the duplicate mutex. */
    public LockedOrganization lockCurrentOrganizationWithWorkspace(
            int additionalWorkspaceId, Permission requiredPermission) {
        return lockCurrentOrganization(additionalWorkspaceId, false, requiredPermission);
    }

    /**
     * Locks the current organization and the actor's membership in an additional workspace.
     *
     * @param additionalWorkspaceId workspace whose active actor membership is required
     * @return locked organization id
     */
    public int lockCurrentOrganizationWithMemberWorkspace(int additionalWorkspaceId) {
        return lockCurrentOrganization(additionalWorkspaceId, true);
    }

    /** Retains both memberships and current permission authority before the duplicate mutex. */
    public LockedOrganization lockCurrentOrganizationWithMemberWorkspace(
            int additionalWorkspaceId, Permission requiredPermission) {
        return lockCurrentOrganization(additionalWorkspaceId, true, requiredPermission);
    }

    private int lockCurrentOrganization(
            Integer additionalWorkspaceId,
            boolean requireAdditionalMembership) {
        return lockActiveOrganization(
            lockCurrentOrganizationRoots(additionalWorkspaceId, requireAdditionalMembership));
    }

    private LockedOrganization lockCurrentOrganization(
            Integer additionalWorkspaceId,
            boolean requireAdditionalMembership,
            Permission requiredPermission) {
        Objects.requireNonNull(requiredPermission, "requiredPermission");
        int orgId = lockCurrentOrganizationRoots(
            additionalWorkspaceId, requireAdditionalMembership);
        LockedPermissionSnapshot authority = workspaceService.lockAndRequirePermissionsSnapshot(
            workspaceService.getCurrentWorkspaceId(),
            Map.of(workspaceService.getCurrentUserId(), Set.of(requiredPermission)));
        return new LockedOrganization(lockActiveOrganization(orgId), authority);
    }

    private int lockCurrentOrganizationRoots(
            Integer additionalWorkspaceId,
            boolean requireAdditionalMembership) {
        int actorId = workspaceService.getCurrentUserId();
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int orgId = workspaceService.getCurrentOrgId();
        lockAvailableAccount(actorId, "Authenticated user is unavailable");
        TreeSet<Integer> workspaceIds = new TreeSet<>();
        workspaceIds.add(workspaceId);
        if (additionalWorkspaceId != null) {
            workspaceIds.add(additionalWorkspaceId);
        }
        for (int lockedWorkspaceId : workspaceIds) {
            if (lockActiveWorkspace(lockedWorkspaceId) != orgId) {
                throw new ForbiddenException("Active workspace organization changed");
            }
        }
        for (int lockedWorkspaceId : workspaceIds) {
            if (lockedWorkspaceId == workspaceId
                    || requireAdditionalMembership) {
                requireActiveMembership(lockedWorkspaceId, actorId);
            }
        }
        return orgId;
    }

    /** Organization mutex and permission authority retained until the caller's transaction ends. */
    public record LockedOrganization(int orgId, LockedPermissionSnapshot authority) {
        public LockedOrganization {
            Objects.requireNonNull(authority, "authority");
        }
    }

    /**
     * Locks an active workspace and organization for principal-free reconciliation work.
     *
     * @param workspaceId workspace being reconciled
     * @return locked organization id
     */
    public int lockBackgroundOrganization(int workspaceId) {
        return lockActiveOrganization(lockActiveWorkspace(workspaceId));
    }

    /**
     * Locks one background capture owner, workspace membership, and organization in lifecycle
     * order so identity and permission decisions remain valid through the tenant transaction.
     *
     * @param workspaceId workspace receiving captured evidence
     * @param userId connected-account owner
     * @return locked organization id
     */
    public int lockBackgroundMemberOrganization(int workspaceId, int userId) {
        lockAvailableAccount(userId, "Connected-account owner is unavailable");
        int orgId = lockActiveWorkspace(workspaceId);
        WorkspaceMember membership =
            workspaceMapper.lockAuthorizationMembership(workspaceId, userId);
        if (membership == null || !"active".equals(membership.getStatus())) {
            throw new ForbiddenException(
                "Connected-account owner is not an active workspace member");
        }
        return lockActiveOrganization(orgId);
    }

    private void lockAvailableAccount(int userId, String unavailableMessage) {
        if (userMapper.lockByIdForShare(userId) == null
                || userMapper.isAccountDeletionReserved(userId)) {
            throw new ForbiddenException(unavailableMessage);
        }
    }

    private int lockActiveWorkspace(int workspaceId) {
        Integer orgId = workspaceMapper.lockActiveWorkspaceForShare(workspaceId);
        if (orgId == null) {
            throw new ResourceNotFoundException(
                "Active workspace is unavailable: " + workspaceId);
        }
        return orgId;
    }

    private int lockActiveOrganization(int orgId) {
        if (organizationMapper.lockActiveByIdForShare(orgId) == null) {
            throw new ForbiddenException("Organization teardown is in progress");
        }
        organizationMapper.lockDuplicateDecision(orgId);
        return orgId;
    }

    private void requireActiveMembership(int workspaceId, int actorId) {
        WorkspaceMember membership =
            workspaceMapper.lockAuthorizationMembership(workspaceId, actorId);
        if (membership == null || !"active".equals(membership.getStatus())) {
            throw new ForbiddenException(
                "Authenticated user is not an active workspace member");
        }
    }
}
