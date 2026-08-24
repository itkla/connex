package ooo.klae.connex.backend.ai.assistant;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.AiWatch;
import ooo.klae.connex.backend.mappers.AiWatchMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Re-evaluates every active watch in every routed workspace on a fixed cadence.
 *
 * <p>The sweep is deliberately stateless. It holds no leadership lease and claims nothing: every
 * at-most-once guarantee lives in the compare-and-set inside {@link AiWatchEvaluationService}, so two
 * instances sweeping the same workspace concurrently, or one instance sweeping the same workspace
 * repeatedly after a restart, still produce at most one notification per state token per cooldown.
 *
 * <p>One watch failing never aborts the rest: a member's broken watch must not silence their
 * colleagues'.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.ai.watches",
    name = "scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AiWatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(AiWatchScheduler.class);

    private final AiWatchMapper watchMapper;
    private final AiWatchEvaluationService evaluationService;
    private final WorkspaceMapper workspaceMapper;
    private final TenantWorkScope tenantWorkScope;
    private final JobRunRecorder jobRunRecorder;
    private final Clock clock;

    /** Runs one evaluation sweep across every routed workspace. */
    @Scheduled(
        fixedDelayString = "${connex.ai.watches.sweep-delay-ms:900000}",
        initialDelayString = "${connex.ai.watches.initial-delay-ms:420000}")
    public void sweep() {
        for (Integer workspaceId : tenantWorkScope.unrouted(workspaceMapper::findWorkspaceIds)) {
            try {
                tenantWorkScope.inWorkspace(workspaceId, () -> sweepWorkspace(workspaceId));
            } catch (RuntimeException exception) {
                log.warn("Ask Connex watch sweep failed workspace={} exceptionClass={}",
                        workspaceId, exception.getClass().getSimpleName());
            }
        }
    }

    /**
     * Evaluates every active, possibly-unexpired watch in one workspace.
     *
     * <p>The expiry bound here is a pre-filter, not the decision. Selection happens before any
     * workspace or member identity is installed, so the only date available is UTC's, while the
     * member declared the expiry in the workspace's reporting calendar — up to a day either side.
     * Selecting one day wider than UTC's own date covers every zone offset and leaves the
     * authoritative comparison to {@link AiWatchEvaluationService}, which runs inside the owner's
     * context and can read the calendar the expiry was validated against.
     */
    void sweepWorkspace(int workspaceId) {
        JobRunDetail started = JobRunDetail.startedUtc();
        String today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(1).toString();
        int evaluated = 0;
        int fired = 0;
        int failed = 0;
        for (AiWatch watch : watchMapper.findEvaluable(workspaceId, today)) {
            evaluated++;
            try {
                if (evaluationService.evaluate(watch) == AiWatchEvaluationService.Outcome.FIRED) {
                    fired++;
                }
            } catch (RuntimeException exception) {
                failed++;
                log.warn("Ask Connex watch evaluation failed workspace={} exceptionClass={}",
                        workspaceId, exception.getClass().getSimpleName());
            }
        }
        jobRunRecorder.record(
                JobRunRecorder.AI_WATCH_EVALUATION,
                workspaceId,
                failed == 0 ? JobRunStatus.SUCCEEDED : JobRunStatus.FAILED,
                new JobRunDetail(started.startedAt(), Map.of(
                        "evaluated", evaluated,
                        "fired", fired,
                        "failed", failed)));
    }
}
