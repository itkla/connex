package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.PersonBreachRow;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Marks first-response SLA deadlines that have passed unanswered and announces each breach to the
 * rule engine, so a workspace escalates with the actions it already has — notify an owner, create a
 * follow-up task, or route the lead to somebody else (#559).
 *
 * <p>The sweep runs as the system actor through {@link AutomationExecutor#runAsObserver}, not
 * {@code runAs}: a breach is an observation the workspace's rules are entitled to react to, not a
 * side effect of a rule action, so the automation-loop guard must not swallow its trigger. No loop
 * is possible in return — the only mutation is the guarded breach stamp, which a contact can take
 * at most once per SLA clock.
 *
 * <p>Work is bounded twice: at most {@code batch-size} contacts per pass and at most
 * {@code max-batches} passes per workspace per run. No cursor is needed — stamping a breach removes
 * the contact from the selection — so a pass that stamps nothing means the remaining rows are
 * failing, and the sweep stops rather than re-reading them until the pass budget runs out. A
 * workspace with a very large backlog drains over several runs instead of holding the scheduler
 * thread, and one failing workspace never starves the rest of the fleet.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.lead-response-sla",
    name = "sweep-enabled",
    matchIfMissing = true)
public class LeadResponseSlaScheduler {

    private static final Logger log = LoggerFactory.getLogger(LeadResponseSlaScheduler.class);

    private final WorkspaceMapper workspaceMapper;
    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final LeadResponseSlaService leadResponseSlaService;
    private final AutomationExecutor automationExecutor;
    private final SystemActor systemActor;
    private final JobRunRecorder jobRunRecorder;

    @Value("${connex.lead-response-sla.batch-size:200}")
    private int batchSize;

    @Value("${connex.lead-response-sla.max-batches:10}")
    private int maxBatches;

    /** Sweeps every active workspace for deadlines that have passed unanswered. */
    @Scheduled(
        fixedDelayString = "${connex.lead-response-sla.sweep-delay-ms:300000}",
        initialDelayString = "${connex.lead-response-sla.sweep-initial-delay-ms:300000}")
    public void sweep() {
        List<String> catalogs = placementRegistry.activeCatalogs();
        Map<String, List<Integer>> workspacesByCatalog = workspacesByCatalog(catalogs);
        for (String catalog : catalogs) {
            try {
                tenantWorkScope.withCatalog(catalog, () -> {
                    sweepCatalog(workspacesByCatalog.getOrDefault(catalog, List.of()));
                    return null;
                });
            } catch (RuntimeException exception) {
                log.warn("Lead response SLA sweep failed for catalog {}: {}",
                    catalog == null ? "(default)" : catalog,
                    exception.getClass().getSimpleName());
            }
        }
    }

    private Map<String, List<Integer>> workspacesByCatalog(List<String> catalogs) {
        Map<String, List<Integer>> grouped = new HashMap<>();
        catalogs.forEach(catalog -> grouped.put(catalog, new ArrayList<>()));
        Set<String> known = grouped.keySet();
        for (int workspaceId : tenantWorkScope.unrouted(workspaceMapper::findWorkspaceIds)) {
            try {
                tenantWorkScope.withWorkspacePlacement(workspaceId, (orgId, catalog) -> {
                    if (!known.contains(catalog)) {
                        throw new IllegalStateException(
                            "Workspace is stored outside its active placement");
                    }
                    grouped.get(catalog).add(workspaceId);
                    return null;
                });
            } catch (RuntimeException exception) {
                log.warn("Lead response SLA sweep skipped workspace {}: {}",
                    workspaceId, exception.getClass().getSimpleName());
            }
        }
        grouped.values().forEach(workspaces -> workspaces.sort(Integer::compareTo));
        return grouped;
    }

    private void sweepCatalog(List<Integer> workspaceIds) {
        for (int workspaceId : workspaceIds) {
            try {
                tenantWorkScope.inWorkspace(workspaceId, () -> sweepWorkspace(workspaceId));
            } catch (RuntimeException exception) {
                log.warn("Lead response SLA sweep failed for workspace {}: {}",
                    workspaceId, exception.getClass().getSimpleName());
            }
        }
    }

    private void sweepWorkspace(int workspaceId) {
        JobRunDetail detail = JobRunDetail.startedUtc();
        try {
            BatchResult result = automationExecutor.runAsObserver(
                workspaceId, systemActor.user(), "system", () -> sweepBatches(workspaceId));
            record(
                workspaceId,
                result.failedCount() == 0 ? JobRunStatus.SUCCEEDED : JobRunStatus.FAILED,
                new JobRunDetail(detail.startedAt(), Map.of(
                    "attemptedCount", result.attemptedCount(),
                    "failedCount", result.failedCount())));
        } catch (RuntimeException exception) {
            record(workspaceId, JobRunStatus.FAILED,
                new JobRunDetail(detail.startedAt(), Map.of("phase", "workspace_sweep")));
            throw exception;
        }
    }

    BatchResult sweepBatches(int workspaceId) {
        int limit = Math.max(1, batchSize);
        int passes = Math.max(1, maxBatches);
        int attempted = 0;
        int failed = 0;
        for (int pass = 0; pass < passes; pass++) {
            List<PersonBreachRow> breaching = leadResponseSlaService.findBreaches(workspaceId, limit);
            if (breaching.isEmpty()) {
                break;
            }
            int stamped = 0;
            for (PersonBreachRow row : breaching) {
                attempted++;
                try {
                    if (leadResponseSlaService.recordBreach(workspaceId, row)) {
                        stamped++;
                    }
                } catch (RuntimeException exception) {
                    failed++;
                    log.warn("Lead response SLA breach failed for workspace {} contact {}: {}",
                        workspaceId, row.id(), exception.getClass().getSimpleName());
                }
            }
            if (stamped == 0 || breaching.size() < limit) {
                break;
            }
        }
        return new BatchResult(attempted, failed);
    }

    private void record(int workspaceId, JobRunStatus status, JobRunDetail detail) {
        try {
            jobRunRecorder.record(JobRunRecorder.LEAD_RESPONSE_SLA, workspaceId, status, detail);
        } catch (RuntimeException exception) {
            log.warn("Job run recording failed jobName={} exceptionClass={}",
                JobRunRecorder.LEAD_RESPONSE_SLA,
                exception.getClass().getSimpleName());
        }
    }

    record BatchResult(int attemptedCount, int failedCount) {
        BatchResult {
            if (attemptedCount < 0 || failedCount < 0 || failedCount > attemptedCount) {
                throw new IllegalArgumentException("Invalid lead response SLA sweep counts");
            }
        }
    }
}
