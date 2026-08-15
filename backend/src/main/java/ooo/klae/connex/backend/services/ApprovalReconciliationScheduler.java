package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.DocumentApproval;
import ooo.klae.connex.backend.mappers.DocumentApprovalMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Reconciles pending approvals whose frozen chains can no longer reach quorum.
 *
 * <p>The tenant and system-actor scope is installed before any transaction opens, because
 * {@code TenantWorkScope} refuses to re-pin the catalog once a transaction holds a connection. Each
 * termination then runs in its own {@link TransactionTemplate} transaction: the termination method
 * is package-private so it stays outside the RBAC-guarded surface, and Spring's proxy-based
 * transaction management only advises public methods, so the caller must own the transaction for
 * the document row lock to be held across the whole write.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.approvals",
    name = "reconciliation-enabled",
    matchIfMissing = true)
public class ApprovalReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(ApprovalReconciliationScheduler.class);

    private final DocumentApprovalMapper approvalMapper;
    private final WorkspaceMapper workspaceMapper;
    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final DocumentApprovalService approvalService;
    private final AutomationExecutor automationExecutor;
    private final SystemActor systemActor;
    private final JobRunRecorder jobRunRecorder;
    private final TransactionTemplate transactionTemplate;
    private final Map<Integer, Integer> cursors = new ConcurrentHashMap<>();

    @Value("${connex.approvals.reconciliation-batch-size:200}")
    private int batchSize;

    /** Sweeps a bounded, rotating batch in every active workspace. */
    @Scheduled(
        fixedDelayString = "${connex.approvals.reconciliation-delay-ms:900000}",
        initialDelayString = "${connex.approvals.reconciliation-initial-delay-ms:900000}")
    public void reconcile() {
        List<String> catalogs = placementRegistry.activeCatalogs();
        Map<String, List<Integer>> workspacesByCatalog = workspacesByCatalog(catalogs);
        for (String catalog : catalogs) {
            try {
                tenantWorkScope.withCatalog(catalog, () -> {
                    reconcileCatalog(workspacesByCatalog.getOrDefault(catalog, List.of()));
                    return null;
                });
            } catch (RuntimeException exception) {
                log.warn("Approval reconciliation failed for catalog {}: {}",
                    catalog == null ? "(default)" : catalog,
                    exception.getClass().getSimpleName());
            }
        }
    }

    private Map<String, List<Integer>> workspacesByCatalog(List<String> catalogs) {
        Map<String, List<Integer>> grouped = new HashMap<>();
        catalogs.forEach(catalog -> grouped.put(catalog, new ArrayList<>()));
        for (int workspaceId : tenantWorkScope.unrouted(workspaceMapper::findWorkspaceIds)) {
            try {
                tenantWorkScope.withWorkspacePlacement(workspaceId, (orgId, catalog) -> {
                    List<Integer> workspaces = grouped.get(catalog);
                    if (workspaces == null) {
                        throw new IllegalStateException(
                            "Workspace is stored outside its active placement");
                    }
                    workspaces.add(workspaceId);
                    return null;
                });
            } catch (RuntimeException exception) {
                log.warn("Approval reconciliation skipped workspace {}: {}",
                    workspaceId, exception.getClass().getSimpleName());
            }
        }
        grouped.values().forEach(workspaces -> workspaces.sort(Integer::compareTo));
        return grouped;
    }

    private void reconcileCatalog(List<Integer> workspaceIds) {
        for (int workspaceId : workspaceIds) {
            try {
                tenantWorkScope.inWorkspace(workspaceId, () -> reconcileWorkspace(workspaceId));
            } catch (RuntimeException exception) {
                log.warn("Approval reconciliation failed for workspace {}: {}",
                    workspaceId, exception.getClass().getSimpleName());
            }
        }
    }

    private void reconcileWorkspace(int workspaceId) {
        JobRunDetail detail = JobRunDetail.startedUtc();
        try {
            BatchResult result = automationExecutor.runAs(
                workspaceId, systemActor.user(), "system", () -> reconcileBatch(workspaceId));
            JobRunStatus status = result.failedCount() == 0
                ? JobRunStatus.SUCCEEDED : JobRunStatus.FAILED;
            record(workspaceId, status, new JobRunDetail(detail.startedAt(), Map.of(
                "attemptedCount", result.attemptedCount(),
                "failedCount", result.failedCount())));
        } catch (RuntimeException exception) {
            record(workspaceId, JobRunStatus.FAILED,
                new JobRunDetail(detail.startedAt(), Map.of("phase", "workspace_reconciliation")));
            throw exception;
        }
    }

    private BatchResult reconcileBatch(int workspaceId) {
        int limit = Math.max(1, batchSize);
        int afterId = cursors.getOrDefault(workspaceId, 0);
        List<DocumentApproval> approvals = afterId == 0
            ? approvalMapper.findPendingForWorkspace(workspaceId, limit)
            : approvalMapper.findPendingForWorkspaceAfter(workspaceId, afterId, limit);
        if (approvals.isEmpty() && afterId != 0) {
            approvals = approvalMapper.findPendingForWorkspace(workspaceId, limit);
        }
        if (!approvals.isEmpty()) {
            cursors.put(workspaceId, approvals.getLast().getId());
        }
        int failedCount = 0;
        for (DocumentApproval approval : approvals) {
            try {
                transactionTemplate.executeWithoutResult(
                    status -> approvalService.terminateIfUnsatisfiable(workspaceId, approval));
            } catch (RuntimeException exception) {
                failedCount++;
                log.warn("Approval reconciliation failed for workspace {} approval {}: {}",
                    workspaceId, approval.getId(), exception.getClass().getSimpleName());
            }
        }
        return new BatchResult(approvals.size(), failedCount);
    }

    private void record(int workspaceId, JobRunStatus status, JobRunDetail detail) {
        try {
            jobRunRecorder.record(
                JobRunRecorder.APPROVAL_RECONCILIATION, workspaceId, status, detail);
        } catch (RuntimeException exception) {
            log.warn("Job run recording failed jobName={} exceptionClass={}",
                JobRunRecorder.APPROVAL_RECONCILIATION,
                exception.getClass().getSimpleName());
        }
    }

    private record BatchResult(int attemptedCount, int failedCount) {
        private BatchResult {
            if (attemptedCount < 0 || failedCount < 0 || failedCount > attemptedCount) {
                throw new IllegalArgumentException("Invalid approval reconciliation counts");
            }
        }
    }
}
