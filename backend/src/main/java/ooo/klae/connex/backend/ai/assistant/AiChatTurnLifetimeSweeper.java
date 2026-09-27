package ooo.klae.connex.backend.ai.assistant;

import java.util.ArrayList;
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

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.beans.AiChatTurnRef;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.services.PlacementRegistry;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Settles non-terminal assistant turns that no run lease covers and that have outlived the
 * absolute turn lifetime.
 *
 * <p>It is the instance-independent generalization of the reader-triggered expiry, and it is
 * deliberately a separate pass from the lease sweeper rather than another branch of one query. A
 * turn with no lease row is a turn no instance ever recorded ownership of: a queued turn whose
 * instance died before it was ever claimed, or any turn claimed by a binary that predates the
 * lease, which is every turn in flight during a rolling deploy. Those settle as today's
 * {@code timed_out}/{@code generation_timeout}, never as an ownership loss nobody can evidence.
 * Two passes rather than one branchy predicate is what makes that rule impossible to lose in a
 * refactor.
 *
 * <p>The window between discovery and the write is closed by the same compare-and-set the
 * reader-triggered expiry uses: the terminal write is predicated on the turn's observed status and
 * on {@code updated_at} still being older than the lifetime boundary, and a claim that landed in
 * between necessarily refreshed that column. Both the discovery and the write compute that
 * boundary in SQL, so the pass compares one database-written column against that same database's
 * clock and no instance's clock skew can widen or narrow it.
 *
 * <p>It shares the lease sweep's cadence, pagination bounds, enable flag, <em>and</em> global
 * per-pass budget, because it is the companion half of the same recovery guarantee and tuning one
 * without the other has no meaning. The global budget is not decoration: without it one pass could
 * issue catalogs × workspaces × batch locking transactions on the shared scheduler thread, and a
 * backlog in one tenant would delay every other tenant's detection past the advertised bound. With
 * the flag off both passes stop and the reader-triggered expiry remains the fallback, exactly as it
 * is today.
 *
 * <p>Job runs are recorded inside {@link TenantWorkScope#inWorkspace}, because {@code job_run} is a
 * tenant-scoped table and the tenant backstop is fail-closed off the request thread: recording
 * after the scope closed would be refused under {@code catalog-per-placement} routing and swallowed
 * as a warning, leaving no evidence in exactly the multi-catalog deployment that needs it.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.ai",
    name = "run-lease-sweep-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AiChatTurnLifetimeSweeper {

    private static final Logger log = LoggerFactory.getLogger(AiChatTurnLifetimeSweeper.class);
    private static final String DEFAULT_CATALOG = "default";
    private static final int LIFETIME_SECONDS =
            Math.toIntExact(AiAssistantTurnBudget.DURABLE_LIFETIME.toSeconds());

    private final AiChatMapper chatMapper;
    private final AiChatTurnPersistenceService persistenceService;
    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final JobRunRecorder jobRunRecorder;
    private final AiProperties properties;
    private final Map<String, AtomicInteger> workspaceCursors = new ConcurrentHashMap<>();
    private final AtomicInteger catalogCursor = new AtomicInteger();

    /** Runs one bounded pass across the catalogs this instance routes to. */
    @Scheduled(
        fixedDelayString = "${connex.ai.run-lease-sweep-delay:30s}",
        initialDelayString = "${connex.ai.run-lease-sweep-initial-delay:60s}")
    public void sweep() {
        int remaining = properties.getRunLeaseSweepMaxSettlements();
        for (String catalog : rotatedCatalogs(placementRegistry.activeCatalogs())) {
            if (remaining <= 0) {
                break;
            }
            try {
                remaining -= sweepCatalog(catalog, remaining);
            } catch (RuntimeException failure) {
                log.warn(
                        "Assistant turn lifetime sweep failed catalog={} exceptionClass={}",
                        catalog == null ? DEFAULT_CATALOG : catalog,
                        failure.getClass().getSimpleName());
            }
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
        int visited = 0;
        int lastVisited = cursor.get();
        for (int workspaceId : workspaceIds) {
            if (visited >= remaining) {
                break;
            }
            visited += sweepWorkspace(workspaceId, remaining - visited);
            lastVisited = workspaceId;
        }
        if (!workspaceIds.isEmpty()) {
            cursor.set(lastVisited);
        }
        return visited;
    }

    private List<Integer> workspacePage(String catalog, int afterWorkspaceId) {
        return tenantWorkScope.withCatalog(
                catalog,
                () -> chatMapper.workspaceIdsWithUnleasedStaleTurns(
                        afterWorkspaceId,
                        LIFETIME_SECONDS,
                        properties.getRunLeaseSweepMaxWorkspaces()));
    }

    private int sweepWorkspace(int workspaceId, int budget) {
        try {
            return tenantWorkScope.inWorkspace(
                    workspaceId, () -> expireAndRecord(workspaceId, budget));
        } catch (RuntimeException failure) {
            log.warn(
                    "Assistant turn lifetime sweep could not route workspaceId={}"
                            + " exceptionClass={}",
                    workspaceId,
                    failure.getClass().getSimpleName());
            return 0;
        }
    }

    private int expireAndRecord(int workspaceId, int budget) {
        long startedNanos = System.nanoTime();
        JobRunDetail started = JobRunDetail.startedUtc();
        try {
            SweepCounts counts = expireWorkspace(workspaceId, budget);
            if (counts.visited() == 0 && counts.failed() == 0) {
                return counts.visited();
            }
            jobRunRecorder.record(
                    JobRunRecorder.AI_CHAT_TURN_LIFETIME_SWEEP,
                    workspaceId,
                    counts.failed() == 0 ? JobRunStatus.SUCCEEDED : JobRunStatus.FAILED,
                    new JobRunDetail(started.startedAt(), metadata(counts, startedNanos)));
            return counts.visited();
        } catch (RuntimeException failure) {
            log.warn(
                    "Assistant turn lifetime sweep failed workspaceId={} exceptionClass={}",
                    workspaceId,
                    failure.getClass().getSimpleName());
            jobRunRecorder.record(
                    JobRunRecorder.AI_CHAT_TURN_LIFETIME_SWEEP,
                    workspaceId,
                    JobRunStatus.FAILED,
                    new JobRunDetail(started.startedAt(), Map.of("phase", "workspace_sweep")));
            return 0;
        }
    }

    private SweepCounts expireWorkspace(int workspaceId, int budget) {
        int batch = Math.min(properties.getRunLeaseSweepBatch(), budget);
        List<AiChatTurnRef> stale =
                chatMapper.findUnleasedStaleTurnRefs(workspaceId, LIFETIME_SECONDS, batch);
        int visited = 0;
        int expired = 0;
        int failed = 0;
        for (AiChatTurnRef turn : stale) {
            if (visited >= budget) {
                break;
            }
            visited++;
            try {
                if (persistenceService.expireUnleasedTurn(
                        workspaceId, turn.sessionId(), turn.id(), LIFETIME_SECONDS)) {
                    expired++;
                }
            } catch (RuntimeException failure) {
                failed++;
                log.warn(
                        "Assistant turn lifetime expiry failed workspaceId={} turnId={}"
                                + " exceptionClass={}",
                        workspaceId,
                        turn.id(),
                        failure.getClass().getSimpleName());
            }
        }
        return new SweepCounts(visited, expired, failed);
    }

    private static Map<String, Object> metadata(SweepCounts counts, long startedNanos) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("visitedCount", counts.visited());
        metadata.put("expiredCount", counts.expired());
        metadata.put("failedCount", counts.failed());
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
    private record SweepCounts(int visited, int expired, int failed) {
    }
}
