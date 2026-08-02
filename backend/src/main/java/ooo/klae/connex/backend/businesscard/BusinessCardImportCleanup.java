package ooo.klae.connex.backend.businesscard;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.BusinessCardImportRequestMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.services.PlacementRegistry;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

import java.util.Map;

/**
 * Bounded catalog-aware retention cleanup for business-card import reservations and claims.
 */
@Component
@RequiredArgsConstructor
public class BusinessCardImportCleanup {
    private static final Logger log = LoggerFactory.getLogger(BusinessCardImportCleanup.class);

    private final BusinessCardProperties properties;
    private final BusinessCardImportRequestMapper mapper;
    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final Clock clock;
    private final JobRunRecorder jobRunRecorder;

    @Scheduled(
        fixedDelayString = "${connex.business-cards.idempotency-cleanup-delay:PT1M}",
        initialDelayString = "${connex.business-cards.idempotency-cleanup-delay:PT1M}")
    public void deleteExpired() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (String catalog : placementRegistry.activeCatalogs()) {
            try {
                tenantWorkScope.withCatalog(catalog, () -> {
                    deleteExpiredInCatalog(cutoff);
                    return null;
                });
            } catch (RuntimeException exception) {
                log.warn("Business-card import retention cleanup failed for catalog {}",
                    catalog == null ? "(default)" : catalog);
            }
        }
    }

    private void deleteExpiredInCatalog(LocalDateTime cutoff) {
        int perWorkspace = properties.getIdempotencyCleanupPerWorkspaceBatchSize();
        List<Integer> workspaceIds = mapper.workspaceIdsWithExpired(cutoff, perWorkspace);
        for (int workspaceId : workspaceIds) {
            JobRunDetail detail = JobRunDetail.started(clock);
            try {
                int deletedCount = mapper.deleteExpired(workspaceId, cutoff, perWorkspace);
                record(workspaceId, JobRunStatus.SUCCEEDED,
                    new JobRunDetail(detail.startedAt(), Map.of("deletedCount", deletedCount)));
            } catch (RuntimeException exception) {
                record(workspaceId, JobRunStatus.FAILED,
                    new JobRunDetail(detail.startedAt(), Map.of("phase", "workspace_cleanup")));
                log.warn(
                    "Business-card import retention cleanup failed for workspace {}",
                    workspaceId);
            }
        }
    }

    private void record(int workspaceId, JobRunStatus status, JobRunDetail detail) {
        try {
            jobRunRecorder.record(
                JobRunRecorder.BUSINESS_CARD_IMPORT_CLEANUP,
                workspaceId,
                status,
                detail);
        } catch (RuntimeException exception) {
            log.warn(
                "Job run recording failed jobName={} exceptionClass={}",
                JobRunRecorder.BUSINESS_CARD_IMPORT_CLEANUP,
                exception.getClass().getSimpleName());
        }
    }
}
