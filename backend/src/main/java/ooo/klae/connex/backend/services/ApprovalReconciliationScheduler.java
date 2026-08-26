package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
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
 * Reconciles pending approvals against the wall clock and the current approver pool: a step that
 * passed its deadline expires or escalates, a chain that can no longer reach quorum is terminated,
 * and an open step approaching its deadline reminds the approvers who can still decide it.
 *
 * <p>The tenant and system-actor scope is installed before any transaction opens, because
 * {@code TenantWorkScope} refuses to re-pin the catalog once a transaction holds a connection. Each
 * workspace batch runs in one {@link TransactionTemplate} transaction that holds the workspace
 * authorization root and one post-lock approver pool across every document. Each termination uses
 * a nested savepoint so one failed document can roll back without releasing the root or poisoning
 * successful siblings; the outer root remains held until the bounded batch finishes.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.approvals",
    name = "reconciliation-enabled",
    matchIfMissing = true)
public class ApprovalReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(ApprovalReconciliationScheduler.class);
    private static final int MAX_CONSECUTIVE_FULL_BATCHES = 16;

    private final DocumentApprovalMapper approvalMapper;
    private final WorkspaceMapper workspaceMapper;
    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final DocumentApprovalService approvalService;
    private final AutomationExecutor automationExecutor;
    private final SystemActor systemActor;
    private final JobRunRecorder jobRunRecorder;
    private final TransactionTemplate transactionTemplate;
    private final Map<Integer, CursorState> cursors = new ConcurrentHashMap<>();

    @Value("${connex.approvals.reconciliation-batch-size:200}")
    private int batchSize;

    /** Sweeps a bounded, rotating batch in every active workspace. */
    @Scheduled(
        fixedDelayString = "${connex.approvals.reconciliation-delay-ms:900000}",
        initialDelayString = "${connex.approvals.reconciliation-initial-delay-ms:900000}")
    public void reconcile() {
        List<String> catalogs = placementRegistry.activeCatalogs();
        Map<String, List<Integer>> workspacesByCatalog = workspacesByCatalog(catalogs);
        Set<Integer> activeWorkspaceIds = workspacesByCatalog.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.toSet());
        cursors.keySet().retainAll(activeWorkspaceIds);
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

    BatchResult reconcileBatch(int workspaceId) {
        BatchResult result = transactionTemplate.execute(
            status -> reconcileLockedBatch(workspaceId));
        return Objects.requireNonNull(result, "Approval reconciliation transaction returned no result");
    }

    private BatchResult reconcileLockedBatch(int workspaceId) {
        int limit = Math.max(1, batchSize);
        DocumentApprovalService.ApproverPool pool =
            approvalService.reconciliationApproverPool(workspaceId);
        List<DocumentApproval> approvals = selectPendingBatch(workspaceId, limit);
        TransactionTemplate terminationTransaction = terminationTransactionTemplate();
        int failedCount = 0;
        for (DocumentApproval approval : approvals) {
            try {
                terminationTransaction.executeWithoutResult(status ->
                    approvalService.reconcileApproval(workspaceId, approval, pool));
            } catch (RuntimeException exception) {
                failedCount++;
                log.warn("Approval reconciliation failed for workspace {} approval {}: {}",
                    workspaceId, approval.getId(), exception.getClass().getSimpleName());
            }
        }
        return new BatchResult(approvals.size(), failedCount);
    }

    List<DocumentApproval> selectPendingBatch(int workspaceId, int limit) {
        CursorState cursor = cursors.getOrDefault(workspaceId, CursorState.start());
        int afterId = cursor.afterId();
        List<DocumentApproval> approvals = afterId == 0
            ? approvalMapper.findPendingForWorkspace(workspaceId, limit)
            : approvalMapper.findPendingForWorkspaceAfter(workspaceId, afterId, limit);
        cursors.put(workspaceId, nextCursor(cursor, approvals, limit));
        return approvals;
    }

    private CursorState nextCursor(
            CursorState cursor, List<DocumentApproval> approvals, int limit) {
        if (approvals.size() < limit) {
            return CursorState.start();
        }
        int lastId = approvals.getLast().getId();
        if (cursor.wrapHighWaterId() != null) {
            return lastId >= cursor.wrapHighWaterId()
                ? new CursorState(lastId, 0, null)
                : new CursorState(lastId, 0, cursor.wrapHighWaterId());
        }
        if (cursor.consecutiveFullBatches() + 1 >= MAX_CONSECUTIVE_FULL_BATCHES) {
            return new CursorState(0, 0, lastId);
        }
        return new CursorState(lastId, cursor.consecutiveFullBatches() + 1, null);
    }

    private TransactionTemplate terminationTransactionTemplate() {
        TransactionTemplate termination = new TransactionTemplate(Objects.requireNonNull(
            transactionTemplate.getTransactionManager(),
            "Approval reconciliation requires a transaction manager"));
        termination.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        return termination;
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

    record BatchResult(int attemptedCount, int failedCount) {
        BatchResult {
            if (attemptedCount < 0 || failedCount < 0 || failedCount > attemptedCount) {
                throw new IllegalArgumentException("Invalid approval reconciliation counts");
            }
        }
    }

    private record CursorState(
            int afterId, int consecutiveFullBatches, Integer wrapHighWaterId) {
        private CursorState {
            if (afterId < 0 || consecutiveFullBatches < 0
                    || consecutiveFullBatches >= MAX_CONSECUTIVE_FULL_BATCHES
                    || (wrapHighWaterId != null && wrapHighWaterId <= 0)) {
                throw new IllegalArgumentException("Invalid approval reconciliation cursor");
            }
        }

        private static CursorState start() {
            return new CursorState(0, 0, null);
        }
    }
}
