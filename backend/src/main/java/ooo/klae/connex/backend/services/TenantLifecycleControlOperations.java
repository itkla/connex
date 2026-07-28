package ooo.klae.connex.backend.services;

import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.tenant.TenantLifecycleProperties;

/** Short control-plane transactions for lifecycle roots and operation leases. */
@Service
@RequiredArgsConstructor
public class TenantLifecycleControlOperations {
    private static final String EXPORT = "export";
    private static final String TEARDOWN = "teardown";

    private final TenantLifecycleControlMapper mapper;
    private final TenantLifecycleProperties properties;

    /** Atomically validates an active workspace and acquires an export lease. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AcquiredWorkspace acquireExport(
            int orgId,
            int workspaceId,
            int actorId) {
        WorkspaceLifecycleRef workspace = mapper.lockActiveWorkspaceForExport(orgId, workspaceId);
        if (workspace == null) {
            WorkspaceLifecycleRef existing = mapper.findWorkspaceInOrg(orgId, workspaceId);
            if (existing == null) {
                throw new ResourceNotFoundException("Workspace not found");
            }
            throw new ConflictException("Workspace teardown is in progress");
        }
        if (!mapper.isOrgAdminForLifecycle(orgId, actorId)) {
            throw new ForbiddenException("Organization administrator access required");
        }
        OperationLease lease = insertLease(orgId, workspaceId, EXPORT);
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
     * Clears export leases older than the export request timeout so a killed or
     * crashed streaming request cannot permanently consume its organization's
     * concurrent-export budget. Teardown leases keep failing closed for
     * privileged operator clearance.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reapStaleExportLeases() {
        return mapper.deleteStaleOperationLeases(
            EXPORT,
            exportLeaseMaxAgeSeconds(),
            properties.getTableBatchSize());
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

    /** Counts export leases that a fenced workspace must drain before sweeping. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public int countExportLeases(int workspaceId) {
        return mapper.countOperationLeases(workspaceId, EXPORT);
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
        WorkspaceLifecycleRef workspace = mapper.lockWorkspaceInOrg(orgId, workspaceId);
        boolean rootExists = workspace != null;
        if (workspace == null) {
            workspace = mapper.lockCleanupTombstoneInOrg(orgId, workspaceId);
        }
        if (workspace == null) {
            throw new ResourceNotFoundException("Workspace not found");
        }
        requireOwner(orgId, actorId);
        requireNoOpenWorkspaceSubjectRequests(orgId, workspaceId);
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
        if (mapper.lockOrganization(orgId) == null) {
            throw new ResourceNotFoundException("Organization not found");
        }
        requireOwner(orgId, actorId);
        requireNoOpenOrganizationSubjectRequests(orgId);
        mapper.markOrganizationTearingDown(orgId);
    }

    /** Deletes an already verified workspace root and its cascading control rows. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteWorkspaceRoot(
            int orgId,
            int workspaceId,
            int actorId,
            OperationLease teardownLease) {
        WorkspaceLifecycleRef workspace = mapper.lockWorkspaceInOrg(orgId, workspaceId);
        WorkspaceLifecycleRef tombstone = workspace == null
            ? mapper.lockCleanupTombstoneInOrg(orgId, workspaceId)
            : null;
        if (workspace == null && tombstone == null) {
            throw new ResourceNotFoundException("Workspace not found");
        }
        requireOwner(orgId, actorId);
        if (!mapper.ownsOperationLease(
                workspaceId,
                TEARDOWN,
                teardownLease.token())) {
            throw new IllegalStateException("Workspace teardown lease was not owned at terminal deletion");
        }
        if (workspace != null) {
            mapper.clearSsoJitWorkspace(orgId, workspaceId);
            mapper.clearSubjectRequestWorkspaceLinks(orgId, workspaceId);
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
        if (mapper.lockCleanupTombstoneInOrg(orgId, workspaceId) == null) {
            throw new ResourceNotFoundException("Workspace cleanup marker not found");
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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteFederatedIdentityBatch(int orgId, int limit) {
        return mapper.deleteFederatedIdentityBatch(orgId, limit);
    }

    /** Deletes a verified empty organization root and its cascading control rows. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteOrganizationRoot(int orgId, int actorId) {
        if (mapper.lockOrganization(orgId) == null) {
            throw new ResourceNotFoundException("Organization not found");
        }
        requireOwner(orgId, actorId);
        if (mapper.countWorkspaces(orgId) != 0
                || mapper.countCleanupTombstones(orgId) != 0) {
            throw new IllegalStateException("Organization still has workspace roots");
        }
        if (mapper.deleteOrganization(orgId) != 1) {
            throw new IllegalStateException("Organization lifecycle root was not deleted");
        }
    }

    private OperationLease insertLease(int orgId, int workspaceId, String kind) {
        if (EXPORT.equals(kind)
                && mapper.countRecentOperationLeasesInOrg(orgId, EXPORT, exportLeaseMaxAgeSeconds())
                    >= properties.getMaxConcurrentExports()) {
            throw new TooManyRequestsException(
                "Too many tenant exports are already streaming; retry shortly");
        }
        OperationLease lease = new OperationLease(
            orgId,
            workspaceId,
            kind,
            UUID.randomUUID().toString());
        try {
            mapper.insertOperationLease(orgId, workspaceId, kind, lease.token());
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("Tenant teardown is already in progress");
        }
        return lease;
    }

    private void requireNoOpenWorkspaceSubjectRequests(int orgId, int workspaceId) {
        if (mapper.countOpenSubjectRequestsForWorkspace(orgId, workspaceId) > 0) {
            throw new ConflictException(
                "An open data-subject request still references this workspace");
        }
    }

    private void requireNoOpenOrganizationSubjectRequests(int orgId) {
        if (mapper.countOpenSubjectRequestsForOrg(orgId) > 0) {
            throw new ConflictException(
                "An open data-subject request is still recorded for this organization");
        }
    }

    private long exportLeaseMaxAgeSeconds() {
        return Math.max(1, properties.getExportTimeout().toSeconds());
    }

    private void requireOwner(int orgId, int actorId) {
        if (!mapper.isOrgOwnerForLifecycle(orgId, actorId)) {
            throw new ForbiddenException("Organization owner access required");
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
