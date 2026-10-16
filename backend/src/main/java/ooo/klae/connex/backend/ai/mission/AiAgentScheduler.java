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
    private final JobRunRecorder jobs;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
            Thread.ofPlatform().daemon().name("connex-ai-mission-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private int workspaceCursor;

    /** Starts at most one bounded page without holding the shared maintenance scheduler during egress. */
    @Scheduled(fixedDelayString = "${connex.ai.mission-sweep-delay:5s}", initialDelay = 30000)
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
            JobRunDetail detail = JobRunDetail.startedUtc();
            try {
                scope.inWorkspace(workspaceId, () -> {
                    for (var expired : runs.expiredRuns(workspaceId, 25)) {
                        persistence.expire(expired);
                    }
                    if (!properties.isMissionsEnabled()) {
                        return null;
                    }
                    for (var candidate : runs.dueRuns(workspaceId, 1)) {
                        try {
                            runner.run(candidate);
                        } catch (RuntimeException refusal) {
                            persistence.defer(candidate);
                        }
                    }
                    return null;
                });
                jobs.record(JobRunRecorder.AI_AGENT_SCHEDULER, workspaceId, JobRunStatus.SUCCEEDED, detail);
            } catch (RuntimeException failure) {
                jobs.record(JobRunRecorder.AI_AGENT_SCHEDULER, workspaceId, JobRunStatus.FAILED, detail);
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
