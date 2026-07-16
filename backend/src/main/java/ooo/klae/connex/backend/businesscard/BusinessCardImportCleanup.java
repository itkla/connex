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
import ooo.klae.connex.backend.services.PlacementRegistry;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

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

    @Scheduled(
        fixedDelayString = "${connex.business-cards.idempotency-cleanup-delay:PT1H}",
        initialDelayString = "${connex.business-cards.idempotency-cleanup-delay:PT1H}")
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
        int remaining = properties.getIdempotencyCleanupBatchSize();
        List<Integer> workspaceIds = mapper.workspaceIdsWithExpired(cutoff, remaining);
        for (int workspaceId : workspaceIds) {
            if (remaining <= 0) {
                return;
            }
            remaining -= mapper.deleteExpired(workspaceId, cutoff, remaining);
        }
    }
}
