package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.DealDuplicateReviewProofMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Bounded catalog-aware retention cleanup for expired deal duplicate review proofs. */
@Component
@RequiredArgsConstructor
public class DealDuplicateReviewProofCleanup {
    private static final Logger log =
        LoggerFactory.getLogger(DealDuplicateReviewProofCleanup.class);
    private static final int WORKSPACE_BATCH_SIZE = 100;
    private static final int PROOF_BATCH_SIZE = 100;

    private final DealDuplicateReviewProofMapper mapper;
    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;

    /** Removes one bounded page of expired proofs from every active tenant catalog. */
    @Scheduled(
        fixedDelayString = "${connex.duplicate-preflight.review-proof-cleanup-delay:PT1M}",
        initialDelayString = "${connex.duplicate-preflight.review-proof-cleanup-delay:PT1M}")
    public void deleteExpired() {
        for (String catalog : placementRegistry.activeCatalogs()) {
            try {
                tenantWorkScope.withCatalog(catalog, () -> {
                    for (int workspaceId : mapper.workspaceIdsWithExpired(WORKSPACE_BATCH_SIZE)) {
                        mapper.deleteExpired(workspaceId, PROOF_BATCH_SIZE);
                    }
                    return null;
                });
            } catch (RuntimeException exception) {
                log.warn(
                    "Deal duplicate review-proof cleanup failed for catalog {}",
                    catalog == null ? "(default)" : catalog);
            }
        }
    }
}
