package ooo.klae.connex.backend.services;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.FederatedIdentity;
import ooo.klae.connex.backend.dto.OrganizationLifecycleRef;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.OpenDataSubjectRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

/** Short control-plane transactions for lifecycle roots and operation leases. */
@Service
@RequiredArgsConstructor
public class TenantLifecycleControlOperations {
    private static final String EXPORT = "export";
    private static final String TEARDOWN = "teardown";
    static final String EXPORT_BUSY_MESSAGE =
        "Too many tenant exports are already streaming; retry shortly";
    private static final int MYSQL_NOWAIT_ERROR = 3572;
    private static final String MYSQL_GENERAL_ERROR_STATE = "HY000";
    private static final int EXPIRED_GRANT_CLEANUP_LIMIT = 100;

    private final TenantLifecycleControlMapper mapper;
    private final UserMapper userMapper;

    /** Atomically validates an active workspace and acquires an export lease. */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public AcquiredWorkspace acquireExport(
            int orgId,
            int workspaceId,
            int actorId) {
        WorkspaceLifecycleRef workspace = lockExportTarget(orgId, workspaceId, actorId);
        OperationLease lease = acquireExportLease(orgId, workspaceId);
        return new AcquiredWorkspace(workspace, lease);
    }

    /** Persists one bounded export grant after locked organization-admin revalidation. */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public void issueExportGrant(
            int orgId,
            int workspaceId,
            int actorId,
            byte[] sessionHash,
            byte[] tokenHash,
            LocalDateTime expiresAt,
            LocalDateTime now) {
        lockExportTarget(orgId, workspaceId, actorId);
        mapper.deleteExpiredExportGrants(now, EXPIRED_GRANT_CLEANUP_LIMIT);
        mapper.deleteExportGrantForBinding(orgId, workspaceId, actorId, sessionHash);
        if (mapper.insertExportGrant(
                tokenHash,
                sessionHash,
                orgId,
                workspaceId,
                actorId,
                expiresAt) != 1) {
            throw new IllegalStateException("Tenant export grant was not persisted");
        }
    }

    /** Atomically consumes an exact bound grant and acquires its export lease. */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public AcquiredWorkspace redeemExportGrant(
            int orgId,
            int workspaceId,
            int actorId,
            byte[] sessionHash,
            byte[] tokenHash,
            LocalDateTime now) {
        WorkspaceLifecycleRef workspace = lockExportTarget(orgId, workspaceId, actorId);
        if (mapper.consumeExportGrant(
                tokenHash,
                sessionHash,
                orgId,
                workspaceId,
                actorId,
                now) != 1) {
            throw new ForbiddenException("Tenant export download grant is invalid or expired");
        }
        OperationLease lease = acquireExportLease(orgId, workspaceId);
        return new AcquiredWorkspace(workspace, lease);
    }

    /** Requires a lifecycle owner even after ordinary organization access is fenced. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void requireLifecycleOwner(int orgId, int actorId) {
        requireOwner(orgId, actorId);
    }

    /** Releases exactly the export or teardown lease owned by its token. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(OperationLease lease) {
        if (mapper.deleteOperationLease(
                lease.workspaceId(), lease.kind(), lease.token()) != 1) {
            throw new IllegalStateException("Tenant operation lease was not owned by this operation");
        }
    }

    /** Releases a teardown lease when its workspace root still exists. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseIfPresent(OperationLease lease) {
        mapper.deleteOperationLease(
            lease.workspaceId(),
            lease.kind(),
            lease.token());
    }

    /**
     * Refuses workspace teardown while an open APPI data-subject request still
     * points at that workspace, before the strict start audit and while the
     * organization is still resolvable, so an administrator can finish the
     * obligation and retry. This unlocked read only reports the state ahead of
     * the fence; the authoritative refusal runs under the workspace lock in
     * {@link #acquireWorkspaceTeardown(int, int, int)}.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void requireNoOpenSubjectRequestsForWorkspace(int orgId, int workspaceId) {
        requireNoOpenWorkspaceSubjectRequests(orgId, workspaceId);
    }

    /**
     * Refuses organization teardown while any APPI data-subject request is still
     * open. Deleting the organization root cascades the request rows themselves
     * away, so an unfinished obligation must be closed first rather than erased.
     * This unlocked read only reports the state ahead of the fence; the
     * authoritative refusal runs under the organization lock in
     * {@link #markOrganizationTearingDown(int, int)}.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void requireNoOpenSubjectRequestsForOrganization(int orgId) {
        requireNoOpenOrganizationSubjectRequests(orgId);
    }

    /**
     * Marks an exact workspace as tearing down and acquires its exclusive
     * teardown lease. The open data-subject request refusal runs here, under the
     * exclusive workspace lock that subject-linked writes contend for, so a
     * request committed after an earlier unlocked check still refuses the fence.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AcquiredWorkspace acquireWorkspaceTeardown(
            int orgId,
            int workspaceId,
            int actorId) {
        lockActor(actorId);
        WorkspaceLifecycleRef workspace = mapper.lockWorkspaceInOrg(workspaceId);
        boolean rootExists = workspace != null;
        if (workspace == null) {
            workspace = mapper.lockCleanupTombstoneInOrg(workspaceId);
        }
        requireWorkspaceInOrg(workspace, orgId, "Workspace not found");
        if (mapper.lockOrganization(orgId) == null) {
            throw new ResourceNotFoundException("Organization not found");
        }
        requireOwner(orgId, actorId);
        requireNoOpenWorkspaceSubjectRequests(orgId, workspaceId);
        requireNoOperationLeasesForWorkspace(workspaceId);
        OperationLease lease = insertLease(orgId, workspaceId, TEARDOWN);
        if (rootExists) {
            mapper.markWorkspaceTearingDown(orgId, workspaceId);
            mapper.clearSsoJitWorkspace(orgId, workspaceId);
        }
        return new AcquiredWorkspace(workspace, lease);
    }

    /** Acquires a teardown lease for a workspace already fenced by organization teardown. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AcquiredWorkspace acquireOrganizationWorkspaceTeardown(
            int orgId,
            int workspaceId,
            int actorId) {
        return acquireWorkspaceTeardown(orgId, workspaceId, actorId);
    }

    /**
     * Fences an organization against ordinary work after locked owner
     * revalidation. The open data-subject request refusal runs under the same
     * exclusive organization lock that every subject request write takes a
     * shared lock on, so no request can be created into the fence window and
     * then cascade away with the organization root.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markOrganizationTearingDown(int orgId, int actorId) {
        lockActor(actorId);
        if (mapper.lockOrganization(orgId) == null) {
            throw new ResourceNotFoundException("Organization not found");
        }
        requireOwner(orgId, actorId);
        requireNoOpenOrganizationSubjectRequests(orgId);
        if (mapper.countOperationLeasesInOrg(orgId) != 0) {
            throw new ConflictException("A tenant operation lease still blocks teardown");
        }
        mapper.markOrganizationTearingDown(orgId);
    }

    /** Deletes an already verified workspace root and its cascading control rows. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteWorkspaceRoot(
            int orgId,
            int workspaceId,
            int actorId,
            OperationLease teardownLease) {
        lockActor(actorId);
        WorkspaceLifecycleRef workspace = mapper.lockWorkspaceInOrg(workspaceId);
        WorkspaceLifecycleRef tombstone = workspace == null
            ? mapper.lockCleanupTombstoneInOrg(workspaceId)
            : null;
        requireWorkspaceInOrg(
            workspace == null ? tombstone : workspace,
            orgId,
            "Workspace not found");
        if (mapper.lockOrganization(orgId) == null) {
            throw new ResourceNotFoundException("Organization not found");
        }
        requireOwner(orgId, actorId);
        if (!mapper.ownsOperationLease(
                workspaceId,
                TEARDOWN,
                teardownLease.token())) {
            throw new IllegalStateException("Workspace teardown lease was not owned at terminal deletion");
        }
        mapper.clearSubjectRequestWorkspaceLinks(orgId, workspaceId);
        if (workspace != null) {
            mapper.clearSsoJitWorkspace(orgId, workspaceId);
            mapper.insertCleanupTombstone(
                workspace.id(),
                workspace.orgId(),
                workspace.name(),
                workspace.slug());
            if (mapper.deleteWorkspace(orgId, workspaceId) != 1) {
                throw new IllegalStateException("Workspace lifecycle root was not deleted");
            }
        }
    }

    /** Removes the durable cleanup route and its exact lease after a clean post-root scan. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeWorkspaceCleanup(
            int orgId,
            int workspaceId,
            int actorId,
            OperationLease teardownLease) {
        lockActor(actorId);
        WorkspaceLifecycleRef tombstone = mapper.lockCleanupTombstoneInOrg(workspaceId);
        requireWorkspaceInOrg(tombstone, orgId, "Workspace cleanup marker not found");
        if (mapper.lockOrganization(orgId) == null) {
            throw new ResourceNotFoundException("Organization not found");
        }
        requireOwner(orgId, actorId);
        if (!mapper.ownsOperationLease(
                workspaceId,
                TEARDOWN,
                teardownLease.token())) {
            throw new IllegalStateException("Workspace teardown lease was not owned at cleanup completion");
        }
        if (mapper.deleteCleanupTombstone(orgId, workspaceId) != 1) {
            throw new IllegalStateException("Workspace cleanup marker was not deleted");
        }
        if (mapper.deleteOperationLease(
                workspaceId,
                TEARDOWN,
                teardownLease.token()) != 1) {
            throw new IllegalStateException("Workspace teardown lease was not deleted");
        }
    }

    /** Removes one bounded page of restrictive org-scoped identity rows. */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public int deleteFederatedIdentityBatch(int orgId, int actorId, int limit) {
        List<FederatedIdentity> discovered =
            mapper.findFederatedIdentityBatch(orgId, limit);
        TreeSet<Integer> userIds = new TreeSet<>();
        for (FederatedIdentity identity : discovered) {
            userIds.add(identity.getUserId());
        }
        userIds.add(actorId);
        for (int userId : userIds) {
            if (userMapper.lockByIdForShare(userId) == null) {
                if (userId == actorId) {
                    throw new ForbiddenException("Authenticated user is unavailable");
                }
                throw new ConflictException(
                    "Federated identity cleanup changed; retry organization teardown");
            }
        }
        OrganizationLifecycleRef organization = mapper.lockOrganization(orgId);
        if (organization == null) {
            throw new ResourceNotFoundException("Organization not found");
        }
        if (!"tearing_down".equals(organization.lifecycleState())) {
            throw new ConflictException("Organization teardown is not in progress");
        }
        requireOwner(orgId, actorId);
        List<FederatedIdentity> current =
            mapper.findFederatedIdentityBatch(orgId, limit);
        if (!discovered.equals(current)) {
            throw new ConflictException(
                "Federated identity cleanup changed; retry organization teardown");
        }
        if (current.isEmpty()) {
            return 0;
        }
        List<Integer> identityIds = current.stream()
            .map(FederatedIdentity::getId)
            .toList();
        int deleted = mapper.deleteFederatedIdentityBatch(orgId, identityIds);
        if (deleted != identityIds.size()) {
            throw new ConflictException(
                "Federated identity cleanup changed; retry organization teardown");
        }
        return deleted;
    }

    /** Deletes a verified empty organization root and its cascading control rows. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteOrganizationRoot(int orgId, int actorId) {
        lockActor(actorId);
        if (mapper.lockOrganization(orgId) == null) {
            throw new ResourceNotFoundException("Organization not found");
        }
        requireOwner(orgId, actorId);
        if (mapper.countWorkspaces(orgId) != 0
                || mapper.countCleanupTombstones(orgId) != 0
                || mapper.countOperationLeasesInOrg(orgId) != 0) {
            throw new IllegalStateException("Organization still has workspace roots");
        }
        if (mapper.deleteOrganization(orgId) != 1) {
            throw new IllegalStateException("Organization lifecycle root was not deleted");
        }
    }

    private OperationLease insertLease(int orgId, int workspaceId, String kind) {
        OperationLease lease = new OperationLease(
            orgId,
            workspaceId,
            kind,
            UUID.randomUUID().toString());
        try {
            mapper.insertOperationLease(orgId, workspaceId, kind, lease.token());
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("A tenant operation is already in progress");
        }
        return lease;
    }

    private WorkspaceLifecycleRef lockExportTarget(int orgId, int workspaceId, int actorId) {
        lockActor(actorId);
        WorkspaceLifecycleRef workspace = mapper.lockWorkspaceForShare(workspaceId);
        requireWorkspaceInOrg(workspace, orgId, "Workspace not found");
        if (!"active".equals(workspace.lifecycleState())) {
            throw new ConflictException("Workspace teardown is in progress");
        }
        if (mapper.lockActiveOrganizationForShare(orgId) == null) {
            if (mapper.findOrganization(orgId) == null) {
                throw new ResourceNotFoundException("Organization not found");
            }
            throw new ConflictException("Organization teardown is in progress");
        }
        if (mapper.lockOrgAdminMembershipForUpdate(orgId, actorId) == null) {
            throw new ForbiddenException("Organization administrator access required");
        }
        return workspace;
    }

    private OperationLease acquireExportLease(int orgId, int workspaceId) {
        int capacity = lockExportAdmissionCapacity();
        if (mapper.countGlobalExportLeases() >= capacity) {
            throw new TooManyRequestsException(EXPORT_BUSY_MESSAGE);
        }
        return insertLease(orgId, workspaceId, EXPORT);
    }

    private int lockExportAdmissionCapacity() {
        try {
            int capacity = mapper.lockExportAdmissionCapacityNowait();
            if (capacity < 1 || capacity > TenantExportService.MAX_CONCURRENT_EXPORTS) {
                throw new IllegalStateException("Tenant export admission capacity is invalid");
            }
            return capacity;
        } catch (RuntimeException exception) {
            if (isMySqlNowaitContention(exception)) {
                throw new TooManyRequestsException(EXPORT_BUSY_MESSAGE);
            }
            throw exception;
        }
    }

    private static boolean isMySqlNowaitContention(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && sqlException.getErrorCode() == MYSQL_NOWAIT_ERROR
                    && MYSQL_GENERAL_ERROR_STATE.equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void requireNoOperationLeasesForWorkspace(int workspaceId) {
        if (mapper.countAllOperationLeases(workspaceId) != 0) {
            throw new ConflictException("A tenant operation lease still blocks teardown");
        }
    }

    private void requireNoOpenWorkspaceSubjectRequests(int orgId, int workspaceId) {
        if (mapper.countOpenSubjectRequestsForWorkspace(orgId, workspaceId) > 0) {
            throw new OpenDataSubjectRequestException(
                "An open data-subject request still references this workspace");
        }
    }

    private void requireNoOpenOrganizationSubjectRequests(int orgId) {
        if (mapper.countOpenSubjectRequestsForOrg(orgId) > 0) {
            throw new OpenDataSubjectRequestException(
                "An open data-subject request is still recorded for this organization");
        }
    }

    private void requireOwner(int orgId, int actorId) {
        if (!mapper.isOrgOwnerForLifecycle(orgId, actorId)) {
            throw new ForbiddenException("Organization owner access required");
        }
    }

    private void lockActor(int actorId) {
        if (userMapper.lockByIdForShare(actorId) == null) {
            throw new ForbiddenException("Authenticated user is unavailable");
        }
    }

    private static void requireWorkspaceInOrg(
            WorkspaceLifecycleRef workspace,
            int orgId,
            String message) {
        if (workspace == null || workspace.orgId() != orgId) {
            throw new ResourceNotFoundException(message);
        }
    }

    /** Exact control-plane target and its acquired operation lease. */
    public record AcquiredWorkspace(
            WorkspaceLifecycleRef workspace,
            OperationLease lease) {
    }

    /** Opaque ownership token for one lifecycle operation lease. */
    public record OperationLease(
            int orgId,
            int workspaceId,
            String kind,
            String token) {
    }
}
