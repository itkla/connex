package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Rerunnable startup reconciliation on every normal profile, excluding maintenance/seeder modes. */
@Component
@ConditionalOnProperty(prefix = "connex.maintenance", name = "mode", havingValue = "off", matchIfMissing = true)
@RequiredArgsConstructor
public class AiIdentifierKeyBackfillRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AiIdentifierKeyBackfillRunner.class);

    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final WorkspaceMapper workspaceMapper;
    private final AiIdentifierKeyBackfillTransaction transaction;

    @Override
    public void run(ApplicationArguments arguments) {
        List<String> catalogs = tenantWorkScope.unroutedRead(placementRegistry::activeCatalogs);
        List<Integer> workspaceIds = tenantWorkScope.unroutedRead(workspaceMapper::findWorkspaceIds);
        Map<String, List<Integer>> byCatalog = new HashMap<>();
        for (String catalog : catalogs) {
            byCatalog.put(catalog, new ArrayList<>());
        }
        for (int workspaceId : workspaceIds) {
            try {
                tenantWorkScope.withWorkspacePlacement(workspaceId, (orgId, catalog) -> {
                    List<Integer> group = byCatalog.get(catalog);
                    if (group == null) {
                        throw new IllegalStateException("Identifier backfill workspace is outside its active placement");
                    }
                    group.add(workspaceId);
                    return null;
                });
            } catch (ServiceUnavailableException exception) {
                warnSkipped(workspaceId);
            }
        }
        for (String catalog : catalogs) {
            List<Integer> group = byCatalog.get(catalog);
            group.sort(Integer::compareTo);
            for (int workspaceId : group) {
                try {
                    tenantWorkScope.withWorkspacePlacement(workspaceId, (orgId, resolvedCatalog) -> {
                        if (!Objects.equals(catalog, resolvedCatalog)) {
                            throw new IllegalStateException("Identifier backfill placement changed before sweep");
                        }
                        sweepWorkspace(workspaceId);
                        return null;
                    });
                } catch (ServiceUnavailableException exception) {
                    warnSkipped(workspaceId);
                }
            }
        }
    }

    private void sweepWorkspace(int workspaceId) {
        try {
            long scanned = 0;
            long written = 0;
            long skipped = 0;
            for (String kind : List.of("person", "company", "deal")) {
                int afterId = 0;
                while (true) {
                    AiIdentifierKeyBackfillTransaction.Batch batch = transaction.backfillPage(workspaceId, kind, afterId);
                    scanned = Math.addExact(scanned, batch.scanned());
                    written = Math.addExact(written, batch.written());
                    skipped = Math.addExact(skipped, batch.skipped());
                    if (batch.scanned() == 0) {
                        break;
                    }
                    if (batch.lastId() <= afterId) {
                        throw new IllegalStateException("Identifier key backfill cursor did not advance");
                    }
                    afterId = batch.lastId();
                    if (batch.scanned() < AiIdentifierKeyBackfillTransaction.PAGE_SIZE) {
                        break;
                    }
                }
            }
            log.info("AI identifier key backfill workspace {}: scanned={}, written={}, skipped={}, pending={}",
                    workspaceId, scanned, written, skipped, transaction.pendingCount(workspaceId));
        } catch (RuntimeException exception) {
            log.error("AI identifier key backfill failed for workspace {}; legacy lookup remains active; restart to retry",
                    workspaceId);
        }
    }

    private void warnSkipped(int workspaceId) {
        log.warn("AI identifier key backfill skipped unservable workspace {}; restart to retry", workspaceId);
    }
}
