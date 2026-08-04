package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Fair bounded scheduler for database-leased trigger and run work. */
@Component
@RequiredArgsConstructor
public class WorkflowRuntimeScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRuntimeScheduler.class);
    private static final String DEFAULT_CATALOG = "default";

    private final WorkflowTriggerOutboxMapper outboxMapper;
    private final WorkflowRuntimeClaimTransaction claimTransaction;
    private final WorkflowTriggerOutboxWorker outboxWorker;
    private final WorkflowRunWorker runWorker;
    private final WorkflowRuntimeRetentionService retentionService;
    private final WorkflowRuntimeProperties properties;
    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final JobRunRecorder jobRunRecorder;
    private final Map<String, AtomicInteger> workspaceCursors = new ConcurrentHashMap<>();
    private final AtomicInteger catalogCursor = new AtomicInteger();

    @Scheduled(
        fixedDelayString = "${connex.workflows.runtime.scheduler-delay-ms:5000}",
        initialDelayString = "${connex.workflows.runtime.initial-delay-ms:30000}")
    public void poll() {
        if (!properties.enabled() || !properties.schedulingEnabled()) {
            return;
        }
        sweep();
    }

    public void sweep() {
        List<String> catalogs = rotatedCatalogs(placementRegistry.activeCatalogs());
        int remaining = properties.maxGlobalWorkers();
        for (String catalog : catalogs) {
            if (remaining <= 0) {
                break;
            }
            remaining -= sweepCatalog(catalog, remaining);
        }
    }

    private int sweepCatalog(String catalog, int remaining) {
        AtomicInteger cursor = workspaceCursors.computeIfAbsent(
            catalog == null ? DEFAULT_CATALOG : catalog,
            ignored -> new AtomicInteger());
        List<Integer> workspaceIds = tenantWorkScope.withCatalog(
            catalog,
            () -> outboxMapper.workspaceIdsPage(
                cursor.get(), properties.maxWorkspacesPerSweep()));
        if (workspaceIds.isEmpty() && cursor.get() != 0) {
            cursor.set(0);
            workspaceIds = tenantWorkScope.withCatalog(
                catalog,
                () -> outboxMapper.workspaceIdsPage(
                    0, properties.maxWorkspacesPerSweep()));
        }
        int processed = 0;
        int lastVisited = cursor.get();
        for (int workspaceId : workspaceIds) {
            if (processed >= remaining) {
                break;
            }
            processed += sweepWorkspace(
                workspaceId, Math.min(properties.workspaceQuantum(), remaining - processed));
            lastVisited = workspaceId;
        }
        if (!workspaceIds.isEmpty()) {
            cursor.set(lastVisited);
        }
        return processed;
    }

    private int sweepWorkspace(int workspaceId, int budget) {
        JobRunDetail started = JobRunDetail.startedUtc();
        try {
            int processed = tenantWorkScope.inWorkspace(
                workspaceId, () -> processWorkspace(workspaceId, budget));
            if (processed > 0) {
                jobRunRecorder.record(
                    JobRunRecorder.WORKFLOW_RUNTIME,
                    workspaceId,
                    JobRunStatus.SUCCEEDED,
                    new JobRunDetail(
                        started.startedAt(), Map.of("claimedCount", processed)));
            }
            return processed;
        } catch (RuntimeException failure) {
            log.warn(
                "Workflow runtime workspace sweep failed workspaceId={} exceptionClass={}",
                workspaceId,
                failure.getClass().getSimpleName());
            jobRunRecorder.record(
                JobRunRecorder.WORKFLOW_RUNTIME,
                workspaceId,
                JobRunStatus.FAILED,
                new JobRunDetail(started.startedAt(), Map.of("phase", "workspace_sweep")));
            return 0;
        }
    }

    private int processWorkspace(int workspaceId, int budget) {
        int processed = 0;
        for (; processed < budget; processed++) {
            WorkflowWorkClaim claim = claimTransaction.claimNext(workspaceId);
            if (claim == null) {
                break;
            }
            if (claim.kind() == WorkflowWorkClaim.Kind.TRIGGER) {
                outboxWorker.process(workspaceId, claim.id(), claim.leaseOwner());
            } else {
                runWorker.process(claim);
            }
        }
        retentionService.purge(workspaceId);
        return processed;
    }

    private List<String> rotatedCatalogs(List<String> catalogs) {
        if (catalogs.isEmpty()) {
            return List.of();
        }
        int start = Math.floorMod(catalogCursor.getAndIncrement(), catalogs.size());
        List<String> rotated = new ArrayList<>(catalogs.size());
        for (int offset = 0; offset < catalogs.size(); offset++) {
            rotated.add(catalogs.get((start + offset) % catalogs.size()));
        }
        return rotated;
    }
}
