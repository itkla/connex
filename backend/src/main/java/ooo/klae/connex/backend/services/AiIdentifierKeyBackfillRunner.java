package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Bounded reconciliation on scheduler threads; never participates in application startup readiness. */
@Component
@ConditionalOnProperty(prefix = "connex.maintenance", name = "mode", havingValue = "off", matchIfMissing = true)
@RequiredArgsConstructor
public class AiIdentifierKeyBackfillRunner {
    private static final Logger log = LoggerFactory.getLogger(AiIdentifierKeyBackfillRunner.class);
    static final int BATCHES_PER_INVOCATION = 4;
    private static final List<String> KINDS = List.of("person", "company", "deal");

    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final WorkspaceMapper workspaceMapper;
    private final AiIdentifierKeyBackfillTransaction transaction;
    private int workspaceAfterId;
    private int workspaceId;
    private int kindIndex;
    private int afterId;
    private Integer pinnedOrgId;
    private String pinnedCatalog;
    private long workspaceScanned;
    private long workspaceWritten;
    private long workspaceSkipped;

    /** Resumes at most four pages; each placement is installed before its independent transaction. */
    @Scheduled(scheduler = "aiIdentifierKeyBackfillScheduler",
            fixedDelayString = "${connex.ai.identifier-backfill.delay-ms:30000}",
            initialDelayString = "${connex.ai.identifier-backfill.initial-delay-ms:30000}")
    public synchronized void reconcile() {
        try {
            List<String> catalogs = tenantWorkScope.unroutedRead(placementRegistry::activeCatalogs);
            for (int batch = 0; batch < BATCHES_PER_INVOCATION; batch++) {
                if (workspaceId == 0 && !nextWorkspace()) {
                    break;
                }
                try {
                    tenantWorkScope.withWorkspacePlacement(workspaceId, (orgId, catalog) -> {
                        if (!catalogs.contains(catalog) || (pinnedOrgId != null
                                && (!pinnedOrgId.equals(orgId) || !Objects.equals(pinnedCatalog, catalog)))) {
                            throw new IllegalStateException("Identifier backfill placement changed before page");
                        }
                        pinnedOrgId = orgId;
                        pinnedCatalog = catalog;
                        reconcilePage();
                        return null;
                    });
                } catch (RuntimeException exception) {
                    log.warn("AI identifier key backfill failed for workspace {} exceptionClass={}; fallback remains active",
                            workspaceId, exception.getClass().getSimpleName());
                    finishWorkspace();
                }
            }
        } catch (RuntimeException exception) {
            log.warn("AI identifier key backfill discovery failed exceptionClass={}; retry on next sweep",
                    exception.getClass().getSimpleName());
        }
    }

    private boolean nextWorkspace() {
        List<Integer> ids = tenantWorkScope.unroutedRead(() -> workspaceMapper.findWorkspaceIdsPage(workspaceAfterId, 1));
        if (ids.isEmpty()) {
            workspaceAfterId = 0;
            return false;
        }
        int next = ids.getFirst();
        if (next <= workspaceAfterId) {
            throw new IllegalStateException("Identifier backfill workspace cursor did not advance");
        }
        workspaceId = next;
        workspaceAfterId = next;
        return true;
    }

    private void reconcilePage() {
        String kind = KINDS.get(kindIndex);
        AiIdentifierKeyBackfillTransaction.Batch batch = transaction.backfillPage(workspaceId, kind, afterId);
        if (batch.scanned() > 0 && batch.lastId() <= afterId) {
            throw new IllegalStateException("Identifier backfill row cursor did not advance");
        }
        workspaceScanned += batch.scanned();
        workspaceWritten += batch.written();
        workspaceSkipped += batch.skipped();
        log.info("AI identifier key backfill workspace {} kind={}: scanned={}, written={}, skipped={}, pendingObserved={}",
                workspaceId, kind, batch.scanned(), batch.written(), batch.skipped(), batch.written() + batch.skipped());
        if (batch.scanned() < AiIdentifierKeyBackfillTransaction.PAGE_SIZE) {
            afterId = 0;
            kindIndex++;
            if (kindIndex == KINDS.size()) {
                log.info("AI identifier key backfill completed workspace {}: scanned={}, written={}, pendingObserved={}, unrepairedObserved={}",
                        workspaceId, workspaceScanned, workspaceWritten, workspaceWritten + workspaceSkipped, workspaceSkipped);
                finishWorkspace();
            }
        } else {
            afterId = batch.lastId();
        }
    }

    private void finishWorkspace() {
        workspaceId = 0;
        kindIndex = 0;
        afterId = 0;
        pinnedOrgId = null;
        pinnedCatalog = null;
        workspaceScanned = 0;
        workspaceWritten = 0;
        workspaceSkipped = 0;
    }
}
