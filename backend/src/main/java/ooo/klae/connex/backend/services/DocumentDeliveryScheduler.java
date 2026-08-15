package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.DocumentDelivery;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.DocumentDeliveryMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.signature.SignatureProperties;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Catalog-aware bounded expiry reconciliation for live commercial-document envelopes. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.signature", name = "expiry-enabled", matchIfMissing = true)
public class DocumentDeliveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(DocumentDeliveryScheduler.class);

    private final DocumentDeliveryMapper deliveryMapper;
    private final DealDocumentMapper documentMapper;
    private final DealMapper dealMapper;
    private final DocumentDeliveryLifecycleService lifecycleService;
    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final AutomationExecutor automationExecutor;
    private final SystemActor systemActor;
    private final TransactionTemplate transactionTemplate;
    private final SignatureProperties properties;
    private final JobRunRecorder jobRunRecorder;

    /** Sweeps every active placement without opening a transaction at the scheduler entry point. */
    @Scheduled(
        fixedDelayString = "${connex.signature.expiry-delay-ms:300000}",
        initialDelayString = "${connex.signature.expiry-initial-delay-ms:300000}")
    public void expire() {
        for (String catalog : placementRegistry.activeCatalogs()) {
            try {
                expireCatalog(catalog);
            } catch (RuntimeException exception) {
                log.warn(
                    "Document-delivery expiry failed for one catalog exceptionClass={}",
                    exception.getClass().getSimpleName());
            }
        }
    }

    private void expireCatalog(String catalog) {
        TreeSet<Integer> workspaceIds = tenantWorkScope.withCatalog(
            catalog, () -> new TreeSet<>(deliveryMapper.workspaceIdsWithExpired()));
        for (int workspaceId : workspaceIds) {
            JobRunDetail started = JobRunDetail.startedUtc();
            try {
                tenantWorkScope.inWorkspace(workspaceId, () -> {
                    Integer expired = automationExecutor.runAs(
                        workspaceId,
                        systemActor.user(),
                        "system",
                        () -> transactionTemplate.execute(status -> expireWorkspace(workspaceId)));
                    record(
                        workspaceId,
                        JobRunStatus.SUCCEEDED,
                        new JobRunDetail(
                            started.startedAt(),
                            Map.of("expiredCount", expired == null ? 0 : expired)));
                });
            } catch (RuntimeException exception) {
                log.warn(
                    "Document-delivery expiry failed for workspace {} exceptionClass={}",
                    workspaceId,
                    exception.getClass().getSimpleName());
                try {
                    tenantWorkScope.inWorkspace(workspaceId, () -> record(
                        workspaceId,
                        JobRunStatus.FAILED,
                        new JobRunDetail(started.startedAt(), Map.of("expiredCount", 0))));
                } catch (RuntimeException recordException) {
                    log.warn(
                        "Document-delivery expiry failure could not be recorded workspaceId={} "
                            + "exceptionClass={}",
                        workspaceId,
                        recordException.getClass().getSimpleName());
                }
            }
        }
    }

    private int expireWorkspace(int workspaceId) {
        if (properties.getExpiryBatchSize() <= 0) {
            throw new IllegalStateException("Document-delivery expiry batch size must be positive");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int expired = 0;
        for (int deliveryId : deliveryMapper.findDueDeliveryIds(
                workspaceId, now, properties.getExpiryBatchSize())) {
            DocumentDelivery discovered = deliveryMapper.getById(workspaceId, deliveryId);
            if (discovered == null) {
                continue;
            }
            DealDocument document =
                documentMapper.lockById(workspaceId, discovered.getDocumentId());
            DocumentDelivery delivery = deliveryMapper.lockById(workspaceId, deliveryId);
            if (document == null || delivery == null
                    || !("sent".equals(delivery.getStatus())
                        || "viewed".equals(delivery.getStatus()))
                    || delivery.getExpiresAt() == null
                    || delivery.getExpiresAt().isAfter(now)) {
                continue;
            }
            lockRecipientsAscending(workspaceId, deliveryId);
            Deal deal = dealMapper.getDealById(workspaceId, delivery.getDealId());
            if (deal == null) {
                throw new IllegalStateException("Expired document delivery has no deal");
            }
            if (lifecycleService.terminate(
                    workspaceId,
                    deal,
                    document,
                    delivery,
                    "expired",
                    "Delivery expired",
                    null,
                    "system",
                    null,
                    now)) {
                expired++;
            }
        }
        return expired;
    }

    private void lockRecipientsAscending(int workspaceId, int deliveryId) {
        ArrayList<Integer> ids = new ArrayList<>(
            deliveryMapper.getRecipientIds(workspaceId, deliveryId));
        ids.sort(Integer::compareTo);
        for (int id : ids) {
            if (deliveryMapper.lockRecipient(workspaceId, deliveryId, id) == null) {
                throw new IllegalStateException("Document-delivery recipient disappeared during expiry");
            }
        }
    }

    private void record(
            int workspaceId, JobRunStatus status, JobRunDetail detail) {
        try {
            jobRunRecorder.record(
                JobRunRecorder.DOCUMENT_DELIVERY_EXPIRY, workspaceId, status, detail);
        } catch (RuntimeException exception) {
            log.warn(
                "Document-delivery expiry recording failed workspaceId={} exceptionClass={}",
                workspaceId,
                exception.getClass().getSimpleName());
        }
    }
}
