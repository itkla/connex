package ooo.klae.connex.backend.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.services.PlacementRegistry;
import ooo.klae.connex.backend.services.WorkflowTriggeredSendGate;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

import java.util.Map;

/**
 * Periodically dispatches queued campaign sends. Mirrors the rule scheduler: it fans out over the
 * active catalogs, pins each catalog, enumerates the workspaces with queued sends inside it, and asks
 * the dispatch service to drain each one — with per-catalog and per-workspace failure isolation so a
 * single bad placement never starves the fleet. The claim-first dispatch makes a frequent tick safe.
 * Toggle with {@code connex.delivery.dispatch-enabled} (default false, so tests never send).
 */
@Component
@RequiredArgsConstructor
public class CampaignSendWorker {

    private static final Logger log = LoggerFactory.getLogger(CampaignSendWorker.class);

    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final CampaignSendMapper campaignSendMapper;
    private final CampaignDispatchService campaignDispatchService;
    private final JobRunRecorder jobRunRecorder;
    private final WorkflowTriggeredSendGate triggeredSendGate;

    @Value("${connex.delivery.dispatch-enabled:false}")
    private boolean dispatchEnabled;

    @Scheduled(
        fixedDelayString = "${connex.delivery.dispatch-delay-ms:60000}",
        initialDelayString = "${connex.delivery.dispatch-initial-delay-ms:60000}")
    public void dispatch() {
        if (!dispatchEnabled) {
            return;
        }
        for (String catalog : placementRegistry.activeCatalogs()) {
            try {
                dispatchCatalog(catalog);
            } catch (Exception exception) {
                log.warn("Campaign dispatch sweep failed for catalog {}: {}",
                        catalog == null ? "(default)" : catalog, exception.getMessage());
            }
        }
    }

    private void dispatchCatalog(String catalog) {
        for (int workspaceId : tenantWorkScope.withCatalog(
                catalog, () -> campaignSendMapper.workspaceIdsWithQueuedSends(
                    triggeredSendGate.enabled()))) {
            JobRunDetail detail = JobRunDetail.startedUtc();
            try {
                tenantWorkScope.inWorkspace(workspaceId, () -> {
                    try {
                        int failed = campaignDispatchService.processWorkspace(workspaceId);
                        if (failed > 0) {
                            record(workspaceId, JobRunStatus.FAILED,
                                new JobRunDetail(
                                    detail.startedAt(),
                                    Map.of("phase", "workspace_dispatch",
                                        "failedCount", failed)));
                        } else {
                            record(workspaceId, JobRunStatus.SUCCEEDED, detail);
                        }
                    } catch (RuntimeException exception) {
                        record(workspaceId, JobRunStatus.FAILED,
                            new JobRunDetail(
                                detail.startedAt(),
                                Map.of("phase", "workspace_dispatch")));
                        throw exception;
                    }
                });
            } catch (Exception exception) {
                log.warn("Campaign dispatch skipped for workspace {}: {}", workspaceId, exception.getMessage());
            }
        }
    }

    private void record(int workspaceId, JobRunStatus status, JobRunDetail detail) {
        try {
            jobRunRecorder.record(JobRunRecorder.CAMPAIGN_SEND, workspaceId, status, detail);
        } catch (RuntimeException exception) {
            log.warn(
                "Job run recording failed jobName={} exceptionClass={}",
                JobRunRecorder.CAMPAIGN_SEND,
                exception.getClass().getSimpleName());
        }
    }
}
