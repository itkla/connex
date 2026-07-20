package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Backfills legacy automation rules into versioned workflows before readiness. */
@Component
@ConditionalOnProperty(
    prefix = "connex.maintenance",
    name = "mode",
    havingValue = "off",
    matchIfMissing = true)
@RequiredArgsConstructor
public class LegacyWorkflowBackfillRunner implements ApplicationRunner {

    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final RuleMapper ruleMapper;
    private final LegacyWorkflowBackfillTransaction backfillTransaction;

    @Override
    public void run(ApplicationArguments arguments) {
        List<String> catalogs = tenantWorkScope.unrouted(placementRegistry::activeCatalogs);
        for (String catalog : catalogs) {
            tenantWorkScope.withCatalog(catalog, () -> {
                for (int workspaceId : ruleMapper.workspaceIdsWithRules()) {
                    backfillTransaction.backfillWorkspace(catalog, workspaceId);
                }
                return null;
            });
        }
    }
}
