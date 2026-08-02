package ooo.klae.connex.backend.connectedaccounts.capture;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import ooo.klae.connex.backend.beans.ProviderCaptureSyncState;
import ooo.klae.connex.backend.connectedaccounts.ConnectedCaptureProperties;
import ooo.klae.connex.backend.dto.ProviderCaptureSyncRef;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

import java.util.Map;

/**
 * Fair catalog-aware polling scheduler instantiated only when capture work is authorized.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.connected-capture",
    name = "scheduling-enabled",
    havingValue = "true")
public class ProviderCaptureScheduler {
    private static final Set<String> DEGRADED_STREAM_STATUSES =
        Set.of("retrying", "intervention_required", "paused");

    private static final DateTimeFormatter MYSQL_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private final AtomicLong workspaceCursor = new AtomicLong();

    private final WorkspaceMapper workspaceMapper;
    private final ProviderCaptureMapper captureMapper;
    private final ProviderCaptureWorker worker;
    private final TenantWorkScope tenantWorkScope;
    private final ConnectedCaptureProperties properties;
    private final JobRunRecorder jobRunRecorder;

    /** Runs at most one due stream per workspace per sweep before taking another. */
    @Scheduled(fixedDelayString = "${connex.connected-capture.scheduler-delay:PT5S}")
    public void poll() {
        int visitLimit = properties.getSchedulerBatchSize();
        int afterId = Math.toIntExact(Math.min(
            Integer.MAX_VALUE, workspaceCursor.get()));
        List<Integer> workspaceIds = tenantWorkScope.unrouted(
            () -> workspaceMapper.findWorkspaceIdsPage(afterId, visitLimit));
        if (workspaceIds.isEmpty() && afterId != 0) {
            workspaceIds = tenantWorkScope.unrouted(
                () -> workspaceMapper.findWorkspaceIdsPage(0, visitLimit));
        }
        if (workspaceIds.isEmpty()) {
            workspaceCursor.set(0);
            return;
        }
        int processed = 0;
        for (int workspaceId : workspaceIds) {
            if (processed >= properties.getSchedulerBatchSize()) {
                break;
            }
            JobRunDetail detail = JobRunDetail.startedUtc();
            try {
                int[] processedInWorkspace = {0};
                tenantWorkScope.inWorkspace(workspaceId, () -> {
                    try {
                        List<ProviderCaptureSyncRef> refs = captureMapper.findDueSyncRefs(
                            workspaceId, mysql(Instant.now()), 1);
                        boolean[] degraded = {false};
                        for (ProviderCaptureSyncRef ref : refs) {
                            worker.runPage(workspaceId, ref.syncStateId());
                            processedInWorkspace[0]++;
                            if (leftDegraded(workspaceId, ref.syncStateId())) {
                                degraded[0] = true;
                            }
                        }
                        if (!refs.isEmpty()) {
                            record(workspaceId,
                                degraded[0] ? JobRunStatus.FAILED : JobRunStatus.SUCCEEDED,
                                new JobRunDetail(
                                    detail.startedAt(),
                                    degraded[0]
                                        ? Map.of("phase", "stream_degraded",
                                            "dueCount", refs.size())
                                        : Map.of("dueCount", refs.size())));
                        }
                    } catch (RuntimeException exception) {
                        record(workspaceId, JobRunStatus.FAILED,
                            new JobRunDetail(
                                detail.startedAt(),
                                Map.of("phase", "workspace_sweep")));
                        throw exception;
                    }
                });
                processed += processedInWorkspace[0];
            } catch (RuntimeException exception) {
                log.warn(
                    "Skipping provider capture workspace {}: {}",
                    workspaceId,
                    exception.getClass().getSimpleName());
            }
        }
        workspaceCursor.set(workspaceIds.getLast());
    }

    private static String mysql(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC).format(MYSQL_TIMESTAMP);
    }

    private boolean leftDegraded(int workspaceId, long syncStateId) {
        ProviderCaptureSyncState state = captureMapper.getSyncState(workspaceId, syncStateId);
        return state != null && DEGRADED_STREAM_STATUSES.contains(state.getStatus());
    }

    private void record(int workspaceId, JobRunStatus status, JobRunDetail detail) {
        try {
            jobRunRecorder.record(JobRunRecorder.PROVIDER_CAPTURE, workspaceId, status, detail);
        } catch (RuntimeException exception) {
            log.warn(
                "Job run recording failed jobName={} exceptionClass={}",
                JobRunRecorder.PROVIDER_CAPTURE,
                exception.getClass().getSimpleName());
        }
    }
}
