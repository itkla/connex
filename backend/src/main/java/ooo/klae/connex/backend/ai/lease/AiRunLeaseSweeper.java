package ooo.klae.connex.backend.ai.lease;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.beans.AiRunLeaseRow;
import ooo.klae.connex.backend.mappers.AiRunLeaseMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.services.PlacementRegistry;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Settles durable AI runs whose owning instance stopped proving it still owned them.
 *
 * <p>Detection is driven from {@code ai_run_lease} alone, for any subject kind, and settlement is
 * dispatched through {@link AiRunLeaseSubjectHandler}. That is what makes this one mechanism rather
 * than a turn-specific one: a new leasable subject adds one handler and inherits this pass, its
 * pagination, its budgets, and its reap unchanged.
 *
 * <p>The pass is paginated because it runs every thirty seconds. A rotating catalog cursor and a
 * per-catalog workspace cursor keep one pass bounded by {@code run-lease-sweep-max-workspaces} and
 * {@code run-lease-sweep-max-settlements}, so the discovery probe stays an empty range scan on the
 * lease expiry index rather than a full enumeration of every tenant. The job run records how long
 * each workspace took and how many leases it examined, so a pass stretching past the sweep delay
 * is visible before it silently widens the advertised detection bound.
 *
 * <p>A settlement that throws is logged and counted, and the next lease in the workspace is still
 * attempted: one wedged run must not stop every other tenant's recovery. The budget is spent on
 * leases <em>examined</em> rather than leases settled, so a workspace whose leases all lose their
 * takeover races cannot spin.
 *
 * <p>The tombstone reap runs in the same pass, per workspace, and only for the subject kinds that
 * declare their runs provably shorter than the retention window.
 */
@Component
@ConditionalOnProperty(
    prefix = "connex.ai",
    name = "run-lease-sweep-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AiRunLeaseSweeper {

    private static final Logger log = LoggerFactory.getLogger(AiRunLeaseSweeper.class);
    private static final String DEFAULT_CATALOG = "default";

    private final AiRunLeaseService leaseService;
    private final AiRunLeaseMapper leaseMapper;
    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final JobRunRecorder jobRunRecorder;
    private final AiProperties properties;
    private final Map<AiRunLeaseSubject, AiRunLeaseSubjectHandler> handlers =
            new EnumMap<>(AiRunLeaseSubject.class);
    private final Map<String, AtomicInteger> workspaceCursors = new ConcurrentHashMap<>();
    private final AtomicInteger catalogCursor = new AtomicInteger();

    /**
     * Creates the sweeper and indexes the declared subject handlers.
     *
     * @param leaseService the lease transactional boundary
     * @param leaseMapper lease discovery statements
     * @param placementRegistry the catalogs this instance routes to
     * @param tenantWorkScope tenant routing for off-request work
     * @param jobRunRecorder bounded metadata-only job outcome recording
     * @param properties instance-wide AI configuration
     * @param subjectHandlers every declared subject handler
     */
    public AiRunLeaseSweeper(
            AiRunLeaseService leaseService,
            AiRunLeaseMapper leaseMapper,
            PlacementRegistry placementRegistry,
            TenantWorkScope tenantWorkScope,
            JobRunRecorder jobRunRecorder,
            AiProperties properties,
            List<AiRunLeaseSubjectHandler> subjectHandlers) {
        this.leaseService = leaseService;
        this.leaseMapper = leaseMapper;
        this.placementRegistry = placementRegistry;
        this.tenantWorkScope = tenantWorkScope;
        this.jobRunRecorder = jobRunRecorder;
        this.properties = properties;
        for (AiRunLeaseSubjectHandler handler : subjectHandlers) {
            AiRunLeaseSubjectHandler previous = handlers.put(handler.subject(), handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate AI run lease subject handler for " + handler.subject());
            }
        }
    }

    /** Runs one bounded pass across the catalogs this instance routes to. */
    @Scheduled(
        fixedDelayString = "${connex.ai.run-lease-sweep-delay:30s}",
        initialDelayString = "${connex.ai.run-lease-sweep-initial-delay:60s}")
    public void sweep() {
        List<String> catalogs = rotatedCatalogs(placementRegistry.activeCatalogs());
        int remaining = properties.getRunLeaseSweepMaxSettlements();
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
        List<Integer> workspaceIds = workspacePage(catalog, cursor.get());
        if (workspaceIds.isEmpty() && cursor.get() != 0) {
            cursor.set(0);
            workspaceIds = workspacePage(catalog, 0);
        }
        int examined = 0;
        int lastVisited = cursor.get();
        for (int workspaceId : workspaceIds) {
            if (examined >= remaining) {
                break;
            }
            examined += sweepWorkspace(workspaceId, remaining - examined);
            lastVisited = workspaceId;
        }
        if (!workspaceIds.isEmpty()) {
            cursor.set(lastVisited);
        }
        return examined;
    }

    private List<Integer> workspacePage(String catalog, int afterWorkspaceId) {
        return tenantWorkScope.withCatalog(
                catalog,
                () -> leaseMapper.workspaceIdsWithExpiredLeases(
                        afterWorkspaceId, properties.getRunLeaseSweepMaxWorkspaces()));
    }

    private int sweepWorkspace(int workspaceId, int budget) {
        long startedNanos = System.nanoTime();
        JobRunDetail started = JobRunDetail.startedUtc();
        try {
            SweepCounts counts = tenantWorkScope.inWorkspace(
                    workspaceId, () -> settleWorkspace(workspaceId, budget));
            if (counts.isEmpty()) {
                return counts.examined();
            }
            jobRunRecorder.record(
                    JobRunRecorder.AI_RUN_LEASE_SWEEP,
                    workspaceId,
                    counts.failed() == 0 ? JobRunStatus.SUCCEEDED : JobRunStatus.FAILED,
                    new JobRunDetail(started.startedAt(), metadata(counts, startedNanos)));
            return counts.examined();
        } catch (RuntimeException failure) {
            log.warn(
                    "AI run lease sweep failed workspaceId={} exceptionClass={}",
                    workspaceId,
                    failure.getClass().getSimpleName());
            jobRunRecorder.record(
                    JobRunRecorder.AI_RUN_LEASE_SWEEP,
                    workspaceId,
                    JobRunStatus.FAILED,
                    new JobRunDetail(started.startedAt(), Map.of("phase", "workspace_sweep")));
            return 0;
        }
    }

    private SweepCounts settleWorkspace(int workspaceId, int budget) {
        int batch = Math.min(properties.getRunLeaseSweepBatch(), budget);
        List<AiRunLeaseRow> expired = leaseMapper.findExpiredLeases(workspaceId, batch);
        int examined = 0;
        int settled = 0;
        int failed = 0;
        for (AiRunLeaseRow row : expired) {
            if (examined >= budget) {
                break;
            }
            examined++;
            try {
                if (settleLease(row)) {
                    settled++;
                }
            } catch (RuntimeException failure) {
                failed++;
                log.warn(
                        "AI run lease settlement failed workspaceId={} subjectKind={}"
                                + " subjectId={} exceptionClass={}",
                        row.getWorkspaceId(),
                        row.getSubjectKind(),
                        row.getSubjectId(),
                        failure.getClass().getSimpleName());
            }
        }
        int deleted = leaseService.reapTombstones(
                workspaceId,
                Math.toIntExact(properties.getRunLeaseTombstoneRetention().toSeconds()),
                properties.getRunLeaseSweepBatch());
        return new SweepCounts(examined, settled, failed, deleted);
    }

    private boolean settleLease(AiRunLeaseRow row) {
        AiRunLeaseSubject subject = subjectOf(row.getSubjectKind());
        if (subject == null) {
            log.warn(
                    "AI run lease names an unknown subject kind workspaceId={} subjectKind={}",
                    row.getWorkspaceId(),
                    row.getSubjectKind());
            return false;
        }
        AiRunLeaseSubjectHandler handler = handlers.get(subject);
        if (handler == null) {
            log.warn(
                    "No AI run lease handler owns subject kind {} workspaceId={}",
                    subject,
                    row.getWorkspaceId());
            return false;
        }
        AiRunLeaseKey key =
                new AiRunLeaseKey(row.getWorkspaceId(), subject, row.getSubjectId());
        return handler.settleOrphan(key, row.getEpoch());
    }

    private static AiRunLeaseSubject subjectOf(String wireKey) {
        for (AiRunLeaseSubject subject : AiRunLeaseSubject.values()) {
            if (subject.wireKey().equals(wireKey)) {
                return subject;
            }
        }
        return null;
    }

    private static Map<String, Object> metadata(SweepCounts counts, long startedNanos) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("visitedCount", counts.examined());
        metadata.put("expiredCount", counts.settled());
        metadata.put("failedCount", counts.failed());
        metadata.put("deletedCount", counts.deleted());
        metadata.put("durationMs", (System.nanoTime() - startedNanos) / 1_000_000L);
        return metadata;
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

    /** One workspace's contribution to a pass. */
    private record SweepCounts(int examined, int settled, int failed, int deleted) {
        private boolean isEmpty() {
            return examined == 0 && failed == 0 && deleted == 0;
        }
    }
}
