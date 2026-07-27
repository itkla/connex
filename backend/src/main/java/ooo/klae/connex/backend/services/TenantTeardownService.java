package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.OrganizationLifecycleRef;
import ooo.klae.connex.backend.dto.TenantResidualReport;
import ooo.klae.connex.backend.dto.TenantStorageResidual;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.services.TenantLifecycleAccess.Route;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.AcquiredWorkspace;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.OperationLease;
import ooo.klae.connex.backend.storage.ObjectDeletionRetryQueue;
import ooo.klae.connex.backend.tenant.TenantLifecycleProperties;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.DeleteStage;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.NullifyReference;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Resumable tenant teardown with durable lifecycle fences and bounded
 * tenant-plane transactions. The lifecycle state blocks newly resolved work,
 * but the settle window only bounds the chance of an already-resolved ordinary
 * writer committing later; it does not prove writer drain. The post-root
 * residual scan detects rather than prevents such a late commit. A complete
 * writer-drain protocol is explicitly deferred under issue #853.
 *
 * <p>Retries safely repeat registry preparations, object enqueue operations,
 * and bounded deletes while the control root survives. Export and teardown
 * leases have no automatic expiry, so a process crash can leave a stale lease
 * that fails closed pending privileged operator clearance outside this wave.
 * Before deleting a workspace root, teardown persists a root-independent
 * cleanup tombstone. The tombstone retains its organization placement route
 * until the post-root scan is clean, makes handled failures HTTP-resumable, and
 * prevents organization finalization while cleanup remains.
 * Physical object bytes with no database metadata or quota ledger cannot be
 * enumerated and remain an acknowledged orphan-byte limitation.
 *
 * <p>Control-plane {@code audit_log} and its HMAC chain/checkpoint records are
 * retained. Existing foreign keys null the deleted workspace and organization
 * references while immutable integrity reference snapshots and chain-scope
 * identifiers preserve the verifiable accountability record. The audit trail
 * is outside tenant data return under the DPA.
 */
@Service
@RequiredArgsConstructor
public class TenantTeardownService {
    private static final String WORKSPACE_ACTION = "tenant.workspace.teardown";
    private static final String ORGANIZATION_ACTION = "tenant.organization.teardown";
    private static final long WAIT_CHUNK_NANOS = Duration.ofMillis(100).toNanos();

    private final SessionSecurityService sessionSecurityService;
    private final TenantWorkScope tenantWorkScope;
    private final TenantLifecycleControlMapper controlMapper;
    private final TenantLifecycleControlOperations controlOperations;
    private final TenantLifecycleAccess lifecycleAccess;
    private final TenantTeardownTenantTransaction tenantTransaction;
    private final ObjectDeletionRetryQueue deletionRetryQueue;
    private final AuditService auditService;
    private final TenantLifecycleProperties properties;
    private final Clock clock;

    /** Tears down one exact workspace after owner authorization and slug confirmation. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void teardownWorkspace(
            int orgId,
            int workspaceId,
            int actorId,
            String confirmation) {
        tenantWorkScope.unrouted(() -> {
            teardownWorkspaceUnrouted(orgId, workspaceId, actorId, confirmation);
            return null;
        });
    }

    private void teardownWorkspaceUnrouted(
            int orgId,
            int workspaceId,
            int actorId,
            String confirmation) {
        controlOperations.requireLifecycleOwner(orgId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        WorkspaceLifecycleRef target = controlMapper.findWorkspaceOrCleanupInOrg(
            orgId,
            workspaceId);
        if (target == null) {
            throw new ResourceNotFoundException("Workspace not found");
        }
        requireConfirmation(target.slug(), confirmation);
        auditService.recordStrictIndependentScoped(
            WORKSPACE_ACTION,
            "workspace",
            workspaceId,
            null,
            orgId,
            "workspace:" + workspaceId,
            "Workspace teardown started",
            Map.of("declaredTableCount", TenantLifecycleRegistry.declarations().size()));
        AcquiredWorkspace acquired = controlOperations.acquireWorkspaceTeardown(
            orgId,
            workspaceId,
            actorId);
        teardownAcquiredWorkspace(acquired, actorId, true);
    }

    /** Tears down every workspace and then the exact organization root. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void teardownOrganization(
            int orgId,
            int actorId,
            String confirmation) {
        tenantWorkScope.unrouted(() -> {
            teardownOrganizationUnrouted(orgId, actorId, confirmation);
            return null;
        });
    }

    private void teardownOrganizationUnrouted(
            int orgId,
            int actorId,
            String confirmation) {
        controlOperations.requireLifecycleOwner(orgId, actorId);
        sessionSecurityService.requireRecentAuthentication(actorId);
        OrganizationLifecycleRef organization = controlMapper.findOrganization(orgId);
        if (organization == null) {
            throw new ResourceNotFoundException("Organization not found");
        }
        requireConfirmation(organization.slug(), confirmation);
        int workspaceCount = controlMapper.countWorkspaces(orgId);
        auditService.recordStrictIndependentScoped(
            ORGANIZATION_ACTION,
            "organization",
            orgId,
            null,
            orgId,
            "organization:" + orgId,
            "Organization teardown started",
            Map.of("workspaceCount", workspaceCount));
        try {
            controlOperations.markOrganizationTearingDown(orgId, actorId);
            int afterWorkspaceId = 0;
            while (true) {
                List<WorkspaceLifecycleRef> page = controlMapper.findWorkspacesInOrgAfter(
                    orgId,
                    afterWorkspaceId,
                    properties.getWorkspacePageSize());
                if (page.isEmpty()) {
                    break;
                }
                for (WorkspaceLifecycleRef workspace : page) {
                    AcquiredWorkspace acquired =
                        controlOperations.acquireOrganizationWorkspaceTeardown(
                            orgId,
                            workspace.id(),
                            actorId);
                    teardownAcquiredWorkspace(acquired, actorId, false);
                    afterWorkspaceId = workspace.id();
                }
            }
            while (controlOperations.deleteFederatedIdentityBatch(
                    orgId,
                    properties.getTableBatchSize()) > 0) {
            }
            controlOperations.deleteOrganizationRoot(orgId, actorId);
        } catch (RuntimeException exception) {
            auditService.recordFailureScoped(
                ORGANIZATION_ACTION,
                "organization",
                orgId,
                null,
                orgId,
                "organization:" + orgId,
                "Organization teardown failed",
                exception.getClass().getSimpleName());
            throw exception;
        }
    }

    /**
     * Runs the reusable registry-driven residual scan against a trusted
     * workspace identity, including after ordinary lifecycle resolution is
     * fenced.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TenantResidualReport verifyWorkspaceDeleted(
            WorkspaceLifecycleRef target,
            int actorId) {
        Route route = lifecycleAccess.capture(target, target.orgId());
        return lifecycleAccess.withRoute(
            route,
            actorId,
            () -> residualReport(target.id()));
    }

    private void teardownAcquiredWorkspace(
            AcquiredWorkspace acquired,
            int actorId,
            boolean auditFailure) {
        WorkspaceLifecycleRef workspace = acquired.workspace();
        OperationLease lease = acquired.lease();
        Route route = lifecycleAccess.capture(workspace, workspace.orgId());
        boolean rootDeleted = false;
        boolean completed = false;
        try {
            waitForExportDrain(workspace.id());
            waitFor(properties.getTeardownSettleDelay());
            lifecycleAccess.withRoute(route, actorId, () -> {
                sweepTenant(workspace.id());
                return null;
            });
            TenantResidualReport beforeRoot = lifecycleAccess.withRoute(
                route,
                actorId,
                () -> residualReport(workspace.id()));
            requireClean(beforeRoot, workspace, "before control-root deletion");
            controlOperations.deleteWorkspaceRoot(
                workspace.orgId(),
                workspace.id(),
                actorId,
                lease);
            rootDeleted = true;
            TenantResidualReport afterRoot = lifecycleAccess.withRoute(
                route,
                actorId,
                () -> residualReport(workspace.id()));
            if (!afterRoot.clean()) {
                TenantResidualReport cleanup = lifecycleAccess.withRoute(
                    route,
                    actorId,
                    () -> {
                        sweepTenant(workspace.id());
                        return residualReport(workspace.id());
                    });
                if (cleanup.clean()) {
                    controlOperations.completeWorkspaceCleanup(
                        workspace.orgId(),
                        workspace.id(),
                        actorId,
                        lease);
                    completed = true;
                }
                throw new IllegalStateException(
                    "Tenant residual invariant failed after control-root deletion for workspace "
                        + workspace.id() + "; trusted cleanup clean=" + cleanup.clean());
            }
            controlOperations.completeWorkspaceCleanup(
                workspace.orgId(),
                workspace.id(),
                actorId,
                lease);
            completed = true;
        } catch (RuntimeException exception) {
            if (auditFailure || rootDeleted) {
                recordFailure(workspace, exception);
            }
            throw exception;
        } finally {
            if (!completed) {
                controlOperations.releaseIfPresent(lease);
            }
        }
    }

    private void sweepTenant(int workspaceId) {
        for (TableLifecycle declaration : TenantLifecycleRegistry.declarations().values()) {
            for (TenantLifecycleRegistry.Preparation preparation : declaration.preparations()) {
                tenantTransaction.prepare(
                    workspaceId,
                    declaration,
                    (NullifyReference) preparation);
            }
        }
        enqueueAllObjects(workspaceId);
        deleteStage(workspaceId, DeleteStage.CONTENT);
        drainObjects(workspaceId);
        TenantStorageResidual storage = tenantTransaction.storageResidual(workspaceId);
        if (storage.pendingDeletionCount() != 0
                || storage.usageCount() != 0
                || storage.usageBytes() != 0) {
            throw new ServiceUnavailableException(
                "Tenant object deletion is still pending; retry teardown later");
        }
        deleteStage(workspaceId, DeleteStage.STORAGE_FINALIZATION);
    }

    private void enqueueAllObjects(int workspaceId) {
        String afterKey = "";
        while (true) {
            List<String> page = tenantTransaction.objectKeys(
                workspaceId,
                afterKey,
                properties.getObjectPageSize());
            if (page.isEmpty()) {
                return;
            }
            tenantTransaction.enqueueObjects(
                workspaceId,
                page.stream()
                    .map(key -> requireWorkspaceObjectKey(workspaceId, key))
                    .toList());
            afterKey = page.getLast();
        }
    }

    private void drainObjects(int workspaceId) {
        String afterKey = "";
        while (true) {
            List<String> page = tenantTransaction.objectKeys(
                workspaceId,
                afterKey,
                properties.getObjectPageSize());
            if (page.isEmpty()) {
                return;
            }
            for (String key : page) {
                deletionRetryQueue.processTenantInLifecycleRoute(
                    workspaceId,
                    requireWorkspaceObjectKey(workspaceId, key));
            }
            afterKey = page.getLast();
        }
    }

    private void deleteStage(int workspaceId, DeleteStage stage) {
        for (TableLifecycle declaration : TenantLifecycleRegistry.declarations().values().stream()
                .sorted(Comparator.comparingInt(TableLifecycle::deleteOrder))
                .toList()) {
            if (!declaration.direct() || declaration.deleteStage() != stage) {
                continue;
            }
            while (tenantTransaction.deleteBatch(
                    workspaceId,
                    declaration,
                    properties.getTableBatchSize()) > 0) {
            }
        }
    }

    private TenantResidualReport residualReport(int workspaceId) {
        Map<String, Long> tableRows = new LinkedHashMap<>();
        long totalRows = 0;
        for (TableLifecycle declaration : TenantLifecycleRegistry.declarations().values()) {
            long rows = tenantTransaction.count(workspaceId, declaration);
            tableRows.put(declaration.table(), rows);
            totalRows = Math.addExact(totalRows, rows);
        }
        return new TenantResidualReport(
            workspaceId,
            tableRows,
            totalRows,
            tenantTransaction.storageResidual(workspaceId));
    }

    private void waitForExportDrain(int workspaceId) {
        Instant deadline = clock.instant().plus(properties.getExportLeaseWaitTimeout());
        while (controlOperations.countExportLeases(workspaceId) != 0) {
            if (!clock.instant().isBefore(deadline)) {
                throw new ServiceUnavailableException(
                    "Tenant export is still active; retry teardown later");
            }
            parkUntil(deadline);
        }
    }

    private void waitFor(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        Instant deadline = clock.instant().plus(duration);
        while (clock.instant().isBefore(deadline)) {
            parkUntil(deadline);
        }
    }

    private void parkUntil(Instant deadline) {
        long remaining = Duration.between(clock.instant(), deadline).toNanos();
        LockSupport.parkNanos(Math.min(Math.max(remaining, 1), WAIT_CHUNK_NANOS));
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Tenant teardown wait was interrupted");
        }
    }

    private void requireClean(
            TenantResidualReport report,
            WorkspaceLifecycleRef workspace,
            String phase) {
        if (report.clean()) {
            return;
        }
        throw new IllegalStateException(
            "Tenant residual invariant failed " + phase + " for workspace " + workspace.id());
    }

    private void recordFailure(
            WorkspaceLifecycleRef workspace,
            RuntimeException exception) {
        auditService.recordFailureScoped(
            WORKSPACE_ACTION,
            "workspace",
            workspace.id(),
            null,
            workspace.orgId(),
            "workspace:" + workspace.id(),
            "Workspace teardown failed residual verification",
            exception.getClass().getSimpleName());
    }

    private static void requireConfirmation(String expectedSlug, String confirmation) {
        if (!expectedSlug.equals(confirmation)) {
            throw new BadRequestException("Tenant confirmation does not match its slug");
        }
    }

    private static String requireWorkspaceObjectKey(int workspaceId, String key) {
        String prefix = "workspaces/" + workspaceId + "/";
        if (key == null || !key.startsWith(prefix)) {
            throw new IllegalStateException(
                "Tenant object ledger contains a key outside its workspace prefix");
        }
        return key;
    }
}
