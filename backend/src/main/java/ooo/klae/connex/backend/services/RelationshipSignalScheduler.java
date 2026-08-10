package ooo.klae.connex.backend.services;

import java.util.Map;

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

/** Runs canonical relationship-signal reconciliation in each routed workspace. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.radar",
    name = "scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RelationshipSignalScheduler {
    private static final Logger log =
        LoggerFactory.getLogger(RelationshipSignalScheduler.class);

    private final WorkspaceMapper workspaceMapper;
    private final TenantWorkScope tenantWorkScope;
    private final RelationshipSignalReconciliationService reconciliationService;
    private final JobRunRecorder jobRunRecorder;

    /** Reconciles every active workspace without relying on a request security context. */
    @Scheduled(
        fixedDelayString = "${connex.radar.reconciliation-delay-ms:300000}",
        initialDelayString = "${connex.radar.initial-delay-ms:300000}")
    public void reconcile() {
        for (Integer workspaceId : tenantWorkScope.unrouted(workspaceMapper::findWorkspaceIds)) {
            try {
                tenantWorkScope.inWorkspace(workspaceId, () -> reconcile(workspaceId));
            } catch (RuntimeException exception) {
                log.error(
                    "Relationship signal reconciliation failed workspace={} exceptionClass={}",
                    workspaceId, exception.getClass().getSimpleName());
            }
        }
    }

    private void reconcile(int workspaceId) {
        JobRunDetail started = JobRunDetail.startedUtc();
        try {
            RelationshipSignalReconciliationService.Result result =
                reconciliationService.reconcileWorkspace(workspaceId);
            JobRunStatus status = result.failedCount() == 0
                ? JobRunStatus.SUCCEEDED
                : JobRunStatus.FAILED;
            jobRunRecorder.record(
                JobRunRecorder.RELATIONSHIP_SIGNAL_RECONCILIATION,
                workspaceId,
                status,
                new JobRunDetail(
                    started.startedAt(), Map.of("failedCount", result.failedCount())));
        } catch (RuntimeException exception) {
            jobRunRecorder.record(
                JobRunRecorder.RELATIONSHIP_SIGNAL_RECONCILIATION,
                workspaceId,
                JobRunStatus.FAILED,
                new JobRunDetail(
                    started.startedAt(), Map.of("phase", "workspace_reconciliation")));
            throw exception;
        }
    }
}
