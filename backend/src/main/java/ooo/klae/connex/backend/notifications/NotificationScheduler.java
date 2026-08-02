package ooo.klae.connex.backend.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.services.NotificationReconciliationService;

import java.util.Map;

/**
 * Runs reconciliation per explicit workspace without an authentication context.
 * Workspace enumeration reads the control-plane {@code workspace} table (which
 * only exists on the default catalog), so it runs unrouted — no catalog
 * fan-out; {@code inWorkspace} then pins each workspace's own catalog.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.notifications",
    name = "scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class NotificationScheduler {
    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

    private final WorkspaceMapper workspaceMapper;
    private final TenantWorkScope tenantWorkScope;
    private final NotificationReconciliationService reconciliationService;
    private final JobRunRecorder jobRunRecorder;

    @Scheduled(
        fixedDelayString = "${connex.notifications.reconciliation-delay-ms:300000}",
        initialDelayString = "${connex.notifications.initial-delay-ms:300000}"
    )
    public void reconcileAndPurge() {
        for (Integer workspaceId : tenantWorkScope.unrouted(workspaceMapper::findWorkspaceIds)) {
            try {
                tenantWorkScope.inWorkspace(workspaceId, () -> {
                    JobRunDetail detail = JobRunDetail.startedUtc();
                    try {
                        reconciliationService.reconcileWorkspace(workspaceId, true);
                        int purgedCount = reconciliationService.purgeWorkspace(workspaceId);
                        record(workspaceId, JobRunStatus.SUCCEEDED,
                            new JobRunDetail(detail.startedAt(), Map.of("purgedCount", purgedCount)));
                    } catch (RuntimeException exception) {
                        record(workspaceId, JobRunStatus.FAILED,
                            new JobRunDetail(detail.startedAt(), Map.of("phase", "reconcile_or_purge")));
                        throw exception;
                    }
                });
            } catch (Exception exception) {
                log.error("Scheduled notification reconciliation failed for workspace={}", workspaceId, exception);
            }
        }
    }

    private void record(int workspaceId, JobRunStatus status, JobRunDetail detail) {
        try {
            jobRunRecorder.record(
                JobRunRecorder.NOTIFICATION_RECONCILIATION,
                workspaceId,
                status,
                detail);
        } catch (RuntimeException exception) {
            log.warn(
                "Job run recording failed jobName={} exceptionClass={}",
                JobRunRecorder.NOTIFICATION_RECONCILIATION,
                exception.getClass().getSimpleName());
        }
    }
}
