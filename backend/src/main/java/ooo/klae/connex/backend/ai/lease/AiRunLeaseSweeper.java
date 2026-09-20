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
 * Settles durable AI runs whose owning instance stopped proving it still owned them, and collects
 * the tombstones their releases leave behind.
 *
 * <p>Detection is driven from {@code ai_run_lease} alone, for any subject kind, and settlement is
 * dispatched through {@link AiRunLeaseSubjectHandler}. That is what makes this one mechanism rather
 * than a turn-specific one: a new leasable subject adds one handler and inherits this pass, its
 * pagination, its budgets, and its reap unchanged. Discovery asks only for the subject kinds this
 * binary has a handler for, so a lease this instance could neither settle nor safely retire cannot
 * occupy the head of an oldest-first page and starve genuinely dead owners.
 *
 * <p>The pass is paginated because it runs every thirty seconds. A rotating catalog cursor and a
 * per-catalog workspace cursor keep one pass bounded by {@code run-lease-sweep-max-workspaces} and
 * {@code run-lease-sweep-max-settlements}, so the discovery probe stays an empty range scan on the
 * lease expiry index rather than a full enumeration of every tenant. The job run records how long
 * each workspace took and how many leases it examined, so a pass stretching past the sweep delay
 * is visible before it silently widens the advertised detection bound.
 *
 * <p>A settlement that throws is logged and counted, and the next lease in the workspace is still
 * attempted: one wedged run must not stop every other tenant's recovery. Failure is contained at
 * the catalog boundary too, and at each phase independently: a discovery query aimed at one
 * unreachable or unhealthy dedicated catalog throws before any workspace scope opens, so without
 * that boundary the exception would escape the whole pass and every catalog the rotation had not
 * yet reached would skip both settlement and reaping. With N catalogs and a rotation that advances
 * one position per tick, a healthy catalog sitting directly behind a failing one would then be
 * visited roughly once every N passes, widening dead-owner detection from about one sweep interval
 * to N of them. The budget is spent on leases <em>examined</em> rather than leases settled, so a
 * workspace whose leases all lose their takeover races cannot spin.
 *
 * <p>The reap phase carries no global per-pass budget, and the bound it does have is stated rather
 * than implied: one pass deletes at most {@code run-lease-sweep-max-workspaces} times
 * {@code run-lease-sweep-batch} tombstones per catalog. That is deliberate — a tombstone backlog is
 * bounded work that shrinks on every pass and contends for no subject row lock — but it does mean
 * the reap's cost per pass is the product of two properties rather than a single ceiling.
 *
 * <p>The tombstone reap is a second phase with its <em>own</em> catalog-pinned enumeration and its
 * own workspace cursor, and it runs whether or not the settlement budget was spent. Deriving it
 * from the expired-lease page would have been the defect that matters most here: a workspace whose
 * runs all settle normally never holds an expired lease, so it would never be visited, and its
 * tombstones — one per run it has ever completed — would be retained for the life of the tenant.
 *
 * <p>Every job run is recorded inside {@link TenantWorkScope#inWorkspace}, not after it. The
 * recorder writes to {@code job_run}, a tenant-scoped table, and the tenant backstop is fail-closed
 * off the request thread: recording outside the scope would be refused under
 * {@code catalog-per-placement} routing and swallowed as a warning, leaving the multi-catalog
 * deployment this rotation exists for with no sweep evidence at all. The one case that still
 * records nothing is a workspace whose placement cannot be resolved, which has no catalog to write
 * the row to.
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
    private static final String SETTLEMENT_PHASE = "lease_settlement";
    private static final String REAP_PHASE = "tombstone_reap";

    private final AiRunLeaseService leaseService;
    private final AiRunLeaseMapper leaseMapper;
    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final JobRunRecorder jobRunRecorder;
    private final AiProperties properties;
    private final Map<AiRunLeaseSubject, AiRunLeaseSubjectHandler> handlers =
            new EnumMap<>(AiRunLeaseSubject.class);
    private final List<String> handledSubjectKinds;
    private final Map<String, AtomicInteger> settlementCursors = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> reapCursors = new ConcurrentHashMap<>();
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
        List<String> wireKeys = new ArrayList<>(subjectHandlers.size());
        for (AiRunLeaseSubjectHandler handler : subjectHandlers) {
            AiRunLeaseSubjectHandler previous = handlers.put(handler.subject(), handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate AI run lease subject handler for " + handler.subject());
            }
            wireKeys.add(handler.subject().wireKey());
        }
        this.handledSubjectKinds = List.copyOf(wireKeys);
    }

    /** Runs one bounded settlement pass and one reap pass across this instance's catalogs. */
    @Scheduled(
        fixedDelayString = "${connex.ai.run-lease-sweep-delay:30s}",
        initialDelayString = "${connex.ai.run-lease-sweep-initial-delay:60s}")
    public void sweep() {
        List<String> catalogs = rotatedCatalogs(placementRegistry.activeCatalogs());
        int remaining = properties.getRunLeaseSweepMaxSettlements();
        for (String catalog : catalogs) {
            if (remaining > 0 && !handledSubjectKinds.isEmpty()) {
                try {
                    remaining -= settleCatalog(catalog, remaining);
                } catch (RuntimeException failure) {
                    log.warn(
                            "AI run lease settlement pass failed catalog={} exceptionClass={}",
                            label(catalog),
                            failure.getClass().getSimpleName());
                }
            }
            try {
                reapCatalog(catalog);
            } catch (RuntimeException failure) {
                log.warn(
                        "AI run lease reap pass failed catalog={} exceptionClass={}",
                        label(catalog),
                        failure.getClass().getSimpleName());
            }
        }
    }

    private int settleCatalog(String catalog, int remaining) {
        AtomicInteger cursor = cursor(settlementCursors, catalog);
        List<Integer> workspaceIds = settlementPage(catalog, cursor.get());
        if (workspaceIds.isEmpty() && cursor.get() != 0) {
            cursor.set(0);
            workspaceIds = settlementPage(catalog, 0);
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

    private void reapCatalog(String catalog) {
        if (AiRunLeaseSubject.reapableWireKeys().isEmpty()) {
            return;
        }
        AtomicInteger cursor = cursor(reapCursors, catalog);
        List<Integer> workspaceIds = reapPage(catalog, cursor.get());
        if (workspaceIds.isEmpty() && cursor.get() != 0) {
            cursor.set(0);
            workspaceIds = reapPage(catalog, 0);
        }
        int lastVisited = cursor.get();
        for (int workspaceId : workspaceIds) {
            reapWorkspace(workspaceId);
            lastVisited = workspaceId;
        }
        if (!workspaceIds.isEmpty()) {
            cursor.set(lastVisited);
        }
    }

    private List<Integer> settlementPage(String catalog, int afterWorkspaceId) {
        return tenantWorkScope.withCatalog(
                catalog,
                () -> leaseMapper.workspaceIdsWithExpiredLeases(
                        afterWorkspaceId, properties.getRunLeaseSweepMaxWorkspaces()));
    }

    private List<Integer> reapPage(String catalog, int afterWorkspaceId) {
        return tenantWorkScope.withCatalog(
                catalog,
                () -> leaseMapper.workspaceIdsWithReapableTombstones(
                        afterWorkspaceId,
                        AiRunLeaseSubject.reapableWireKeys(),
                        retentionSeconds(),
                        properties.getRunLeaseSweepMaxWorkspaces()));
    }

    private int sweepWorkspace(int workspaceId, int budget) {
        try {
            return tenantWorkScope.inWorkspace(
                    workspaceId, () -> settleAndRecord(workspaceId, budget));
        } catch (RuntimeException failure) {
            log.warn(
                    "AI run lease sweep could not route workspaceId={} exceptionClass={}",
                    workspaceId,
                    failure.getClass().getSimpleName());
            return 0;
        }
    }

    private int settleAndRecord(int workspaceId, int budget) {
        long startedNanos = System.nanoTime();
        JobRunDetail started = JobRunDetail.startedUtc();
        try {
            SweepCounts counts = settleWorkspace(workspaceId, budget);
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
                    new JobRunDetail(started.startedAt(), Map.of("phase", SETTLEMENT_PHASE)));
            return 0;
        }
    }

    private void reapWorkspace(int workspaceId) {
        try {
            tenantWorkScope.inWorkspace(workspaceId, () -> reapAndRecord(workspaceId));
        } catch (RuntimeException failure) {
            log.warn(
                    "AI run lease reap could not route workspaceId={} exceptionClass={}",
                    workspaceId,
                    failure.getClass().getSimpleName());
        }
    }

    private void reapAndRecord(int workspaceId) {
        long startedNanos = System.nanoTime();
        JobRunDetail started = JobRunDetail.startedUtc();
        try {
            int deleted = leaseService.reapTombstones(
                    workspaceId, retentionSeconds(), properties.getRunLeaseSweepBatch());
            if (deleted == 0) {
                return;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("phase", REAP_PHASE);
            metadata.put("deletedCount", deleted);
            metadata.put("durationMs", elapsedMs(startedNanos));
            jobRunRecorder.record(
                    JobRunRecorder.AI_RUN_LEASE_SWEEP,
                    workspaceId,
                    JobRunStatus.SUCCEEDED,
                    new JobRunDetail(started.startedAt(), metadata));
        } catch (RuntimeException failure) {
            log.warn(
                    "AI run lease reap failed workspaceId={} exceptionClass={}",
                    workspaceId,
                    failure.getClass().getSimpleName());
            jobRunRecorder.record(
                    JobRunRecorder.AI_RUN_LEASE_SWEEP,
                    workspaceId,
                    JobRunStatus.FAILED,
                    new JobRunDetail(started.startedAt(), Map.of("phase", REAP_PHASE)));
        }
    }

    private SweepCounts settleWorkspace(int workspaceId, int budget) {
        int batch = Math.min(properties.getRunLeaseSweepBatch(), budget);
        List<AiRunLeaseRow> expired =
                leaseMapper.findExpiredLeases(workspaceId, handledSubjectKinds, batch);
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
        return new SweepCounts(examined, settled, failed);
    }

    private boolean settleLease(AiRunLeaseRow row) {
        AiRunLeaseSubject subject = subjectOf(row.getSubjectKind());
        AiRunLeaseSubjectHandler handler = subject == null ? null : handlers.get(subject);
        if (handler == null) {
            log.warn(
                    "AI run lease discovery returned an unhandled subject kind {} workspaceId={}",
                    row.getSubjectKind(),
                    row.getWorkspaceId());
            return false;
        }
        AiRunLeaseKey key =
                new AiRunLeaseKey(row.getWorkspaceId(), subject, row.getSubjectId());
        return handler.settleOrphan(key, row.getEpoch());
    }

    private int retentionSeconds() {
        return Math.toIntExact(properties.getRunLeaseTombstoneRetention().toSeconds());
    }

    private static AtomicInteger cursor(Map<String, AtomicInteger> cursors, String catalog) {
        return cursors.computeIfAbsent(label(catalog), ignored -> new AtomicInteger());
    }

    private static String label(String catalog) {
        return catalog == null ? DEFAULT_CATALOG : catalog;
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
        metadata.put("phase", SETTLEMENT_PHASE);
        metadata.put("visitedCount", counts.examined());
        metadata.put("expiredCount", counts.settled());
        metadata.put("failedCount", counts.failed());
        metadata.put("durationMs", elapsedMs(startedNanos));
        return metadata;
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
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

    /** One workspace's contribution to a settlement pass. */
    private record SweepCounts(int examined, int settled, int failed) {
        private boolean isEmpty() {
            return examined == 0 && failed == 0;
        }
    }
}
