package ooo.klae.connex.backend.ai.mission;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import jakarta.annotation.PreDestroy;
import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;

/** Bounded sequential discovery in verified placements; durable claims arbitrate all contenders. */
@Component
@RequiredArgsConstructor
public class AiAgentScheduler {
    private final WorkspaceMapper workspaces;
    private final TenantWorkScope scope;
    private final AiAgentRunMapper runs;
    private final AiAgentSegmentRunner runner;
    private final AiAgentPrivacyInitializer initializer;
    private final AiProperties properties;
    private final AiAgentRunPersistenceService persistence;
    private final AiAgentRealtimePublisher publisher;
    private final JobRunRecorder jobs;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
            Thread.ofPlatform().daemon().name("connex-ai-mission-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private int workspaceCursor;

    /** Starts at most one bounded page without holding the shared maintenance scheduler during egress. */
    @Scheduled(fixedDelayString = "${connex.ai.mission-sweep-delay:5s}",
            initialDelayString = "${connex.ai.mission-sweep-initial-delay:30s}")
    public void sweep() {
        if (!initializer.isReady()) {
            return;
        }
        try {
            worker.execute(this::sweepPage);
        } catch (RejectedExecutionException busyOrStopping) {
            return;
        }
    }

    private void sweepPage() {
        List<Integer> ids = scope.unroutedRead(() -> workspaces.findWorkspaceIdsPage(workspaceCursor, 100));
        if (ids.isEmpty()) {
            workspaceCursor = 0;
            return;
        }
        for (int workspaceId : ids) {
            if (Thread.currentThread().isInterrupted() || !initializer.isReady()) {
                return;
            }
            try {
                scope.inWorkspace(workspaceId, () -> {
                    JobRunDetail detail = JobRunDetail.startedUtc();
                    JobRunStatus outcome = JobRunStatus.SUCCEEDED;
                    try {
                        for (var expired : java.util.stream.Stream.concat(runs.expiredRuns(workspaceId, 25).stream(),
                                runs.reviewExpiredRuns(workspaceId, 25).stream()).toList()) {
                            if (persistence.expire(expired)) {
                                var current = runs.getRun(workspaceId, expired.getId());
                                if (current != null) {
                                    publisher.publish(current, current.getReportMessageId() == null ? "mission_state" : "mission_output");
                                }
                            }
                        }
                        if (properties.isMissionsEnabled()) {
                            for (var interrupted : runs.interruptedReadRuns(workspaceId, 25)) {
                                try {
                                    runner.recoverInterrupted(interrupted);
                                } catch (RuntimeException refusal) {
                                    persistence.deferInterrupted(interrupted);
                                }
                            }
                            for (var candidate : runs.dueRuns(workspaceId, 1)) {
                                try {
                                    runner.run(candidate);
                                } catch (RuntimeException refusal) {
                                    persistence.defer(candidate);
                                }
                            }
                        }
                    } catch (RuntimeException failure) {
                        outcome = JobRunStatus.FAILED;
                    } finally {
                        jobs.record(JobRunRecorder.AI_AGENT_SCHEDULER, workspaceId, outcome, detail);
                    }
                    return null;
                });
            } catch (RuntimeException unavailablePlacement) {
                workspaceCursor = workspaceId;
                continue;
            }
            workspaceCursor = workspaceId;
        }
    }

    /** Interrupts the one local worker; durable attempt recovery owns any unfinished settlement. */
    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
    }
}
