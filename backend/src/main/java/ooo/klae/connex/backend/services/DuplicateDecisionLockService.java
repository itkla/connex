package ooo.klae.connex.backend.services;

import java.util.TreeSet;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/**
 * Serializes candidate-affecting person and company mutations across one organization.
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

    /**
     * Locks the current organization while retaining an additional active workspace root.
     *
     * @param additionalWorkspaceId workspace whose lifecycle must remain active
     * @return locked organization id
     */
    public int lockCurrentOrganizationWithWorkspace(int additionalWorkspaceId) {
        return lockCurrentOrganization(additionalWorkspaceId, false);
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

    private int lockCurrentOrganization(
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
        return lockActiveOrganization(orgId);
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
