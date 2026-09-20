package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import ooo.klae.connex.backend.beans.AiChatTurn;
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
 * on {@code updated_at} still being older than the cutoff, and a claim that landed in between
 * necessarily refreshed that column.
 *
 * <p>It shares the lease sweep's cadence, pagination bounds, and enable flag, because it is the
 * companion half of the same recovery guarantee and tuning one without the other has no meaning.
 * With the flag off both passes stop and the reader-triggered expiry remains the fallback, exactly
 * as it is today.
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

    private final AiChatMapper chatMapper;
    private final AiChatTurnPersistenceService persistenceService;
    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final JobRunRecorder jobRunRecorder;
    private final AiProperties properties;
    private final Clock clock;
    private final Map<String, AtomicInteger> workspaceCursors = new ConcurrentHashMap<>();
    private final AtomicInteger catalogCursor = new AtomicInteger();

    /** Runs one bounded pass across the catalogs this instance routes to. */
    @Scheduled(
        fixedDelayString = "${connex.ai.run-lease-sweep-delay:30s}",
        initialDelayString = "${connex.ai.run-lease-sweep-initial-delay:60s}")
    public void sweep() {
        LocalDateTime cutoff = cutoff();
        for (String catalog : rotatedCatalogs(placementRegistry.activeCatalogs())) {
            try {
                sweepCatalog(catalog, cutoff);
            } catch (RuntimeException failure) {
                log.warn(
                        "Assistant turn lifetime sweep failed catalog={} exceptionClass={}",
                        catalog == null ? DEFAULT_CATALOG : catalog,
                        failure.getClass().getSimpleName());
            }
        }
    }

    private void sweepCatalog(String catalog, LocalDateTime cutoff) {
        AtomicInteger cursor = workspaceCursors.computeIfAbsent(
                catalog == null ? DEFAULT_CATALOG : catalog,
                ignored -> new AtomicInteger());
        List<Integer> workspaceIds = workspacePage(catalog, cursor.get(), cutoff);
        if (workspaceIds.isEmpty() && cursor.get() != 0) {
            cursor.set(0);
            workspaceIds = workspacePage(catalog, 0, cutoff);
        }
        int lastVisited = cursor.get();
        for (int workspaceId : workspaceIds) {
            sweepWorkspace(workspaceId, cutoff);
            lastVisited = workspaceId;
        }
        if (!workspaceIds.isEmpty()) {
            cursor.set(lastVisited);
        }
    }

    private List<Integer> workspacePage(String catalog, int afterWorkspaceId, LocalDateTime cutoff) {
        return tenantWorkScope.withCatalog(
                catalog,
                () -> chatMapper.workspaceIdsWithUnleasedStaleTurns(
                        afterWorkspaceId, cutoff, properties.getRunLeaseSweepMaxWorkspaces()));
    }

    private void sweepWorkspace(int workspaceId, LocalDateTime cutoff) {
        long startedNanos = System.nanoTime();
        JobRunDetail started = JobRunDetail.startedUtc();
        try {
            SweepCounts counts = tenantWorkScope.inWorkspace(
                    workspaceId, () -> expireWorkspace(workspaceId, cutoff));
            if (counts.visited() == 0 && counts.failed() == 0) {
                return;
            }
            jobRunRecorder.record(
                    JobRunRecorder.AI_CHAT_TURN_LIFETIME_SWEEP,
                    workspaceId,
                    counts.failed() == 0 ? JobRunStatus.SUCCEEDED : JobRunStatus.FAILED,
                    new JobRunDetail(started.startedAt(), metadata(counts, startedNanos)));
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
        }
    }

    private SweepCounts expireWorkspace(int workspaceId, LocalDateTime cutoff) {
        List<AiChatTurn> stale = chatMapper.findUnleasedStaleTurns(
                workspaceId, cutoff, properties.getRunLeaseSweepBatch());
        int visited = 0;
        int expired = 0;
        int failed = 0;
        for (AiChatTurn turn : stale) {
            visited++;
            try {
                if (persistenceService.expireUnleasedTurn(
                        workspaceId, turn.getSessionId(), turn.getId(), cutoff)) {
                    expired++;
                }
            } catch (RuntimeException failure) {
                failed++;
                log.warn(
                        "Assistant turn lifetime expiry failed workspaceId={} turnId={}"
                                + " exceptionClass={}",
                        workspaceId,
                        turn.getId(),
                        failure.getClass().getSimpleName());
            }
        }
        return new SweepCounts(visited, expired, failed);
    }

    private LocalDateTime cutoff() {
        return LocalDateTime.ofInstant(
                clock.instant().minus(AiAssistantTurnBudget.DURABLE_LIFETIME), ZoneOffset.UTC);
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
