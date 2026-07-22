package ooo.klae.connex.backend.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.services.PlacementRegistry;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

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
                catalog, campaignSendMapper::workspaceIdsWithQueuedSends)) {
            try {
                tenantWorkScope.inWorkspace(workspaceId,
                        () -> campaignDispatchService.processWorkspace(workspaceId));
            } catch (Exception exception) {
                log.warn("Campaign dispatch skipped for workspace {}: {}", workspaceId, exception.getMessage());
            }
        }
    }
}
