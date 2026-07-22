package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
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

    private static final Logger log = LoggerFactory.getLogger(LegacyWorkflowBackfillRunner.class);

    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final RuleMapper ruleMapper;
    private final LegacyWorkflowBackfillTransaction backfillTransaction;

    @Override
    public void run(ApplicationArguments arguments) {
        List<String> catalogs = tenantWorkScope.unrouted(placementRegistry::activeCatalogs);
        for (String catalog : catalogs) {
            List<Integer> workspaceIds = tenantWorkScope.withCatalog(
                catalog, ruleMapper::workspaceIdsWithRules);
            for (int workspaceId : workspaceIds) {
                try {
                    tenantWorkScope.withWorkspacePlacement(workspaceId, (orgId, resolvedCatalog) -> {
                        if (!Objects.equals(catalog, resolvedCatalog)) {
                            throw new IllegalStateException(
                                "Workspace " + workspaceId + " is stored outside its active placement");
                        }
                        backfillWorkspace(resolvedCatalog, workspaceId);
                        return null;
                    });
                } catch (ServiceUnavailableException exception) {
                    log.warn(
                        "Legacy workflow backfill skipped unservable workspace {}: {}",
                        workspaceId,
                        exception.getMessage());
                }
            }
        }
    }

    private void backfillWorkspace(String catalog, int workspaceId) {
        try {
            backfillTransaction.backfillWorkspace(catalog, workspaceId);
        } catch (IllegalStateException firstFailure) {
            try {
                backfillTransaction.backfillWorkspace(catalog, workspaceId);
            } catch (IllegalStateException finalFailure) {
                finalFailure.addSuppressed(firstFailure);
                throw finalFailure;
            }
        }
    }
}
