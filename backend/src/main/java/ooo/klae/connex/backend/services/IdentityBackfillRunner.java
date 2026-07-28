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
import ooo.klae.connex.backend.services.IdentityBackfillTransaction.IdentityBackfillBatch;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Rerunnable startup sweep that backfills canonical identities and collision membership.
 *
 * <p>Control-plane placement resolution stays fatal: a workspace that cannot be pinned to its
 * active catalog must never be swept, because writing tenant data into the wrong catalog is a
 * containment failure. Sweeping one workspace's own tenant data is not fatal. The sweep is
 * derived, rerunnable state, so a single workspace's failure is logged with its cause and the
 * remaining workspaces — and the application itself — still start.
 */
@Component
@ConditionalOnProperty(
    prefix = "connex.maintenance",
    name = "mode",
    havingValue = "off",
    matchIfMissing = true)
@RequiredArgsConstructor
public class IdentityBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IdentityBackfillRunner.class);
    private static final int PAGE_SIZE = 500;

    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final WorkspaceMapper workspaceMapper;
    private final IdentityBackfillTransaction backfillTransaction;

    @Override
    public void run(ApplicationArguments arguments) {
        List<String> catalogs = tenantWorkScope.unrouted(placementRegistry::activeCatalogs);
        List<Integer> workspaceIds = tenantWorkScope.unrouted(workspaceMapper::findWorkspaceIds);
        Map<String, List<Integer>> workspacesByCatalog = new HashMap<>();
        for (String catalog : catalogs) {
            workspacesByCatalog.put(catalog, new ArrayList<>());
        }
        for (int workspaceId : workspaceIds) {
            try {
                tenantWorkScope.withWorkspacePlacement(workspaceId, (orgId, resolvedCatalog) -> {
                    List<Integer> catalogWorkspaces = workspacesByCatalog.get(resolvedCatalog);
                    if (catalogWorkspaces == null) {
                        throw new IllegalStateException(
                            "Workspace " + workspaceId + " is stored outside its active placement");
                    }
                    catalogWorkspaces.add(workspaceId);
                    return null;
                });
            } catch (ServiceUnavailableException exception) {
                warnSkipped(workspaceId, exception);
            }
        }
        for (String catalog : catalogs) {
            List<Integer> catalogWorkspaces = workspacesByCatalog.getOrDefault(catalog, List.of());
            catalogWorkspaces.sort(Integer::compareTo);
            for (int workspaceId : catalogWorkspaces) {
                try {
                    tenantWorkScope.withWorkspacePlacement(workspaceId, (orgId, resolvedCatalog) -> {
                        if (!Objects.equals(catalog, resolvedCatalog)) {
                            throw new IllegalStateException(
                                "Workspace " + workspaceId + " is stored outside its active placement");
                        }
                        sweepWorkspace(resolvedCatalog, workspaceId);
                        return null;
                    });
                } catch (ServiceUnavailableException exception) {
                    warnSkipped(workspaceId, exception);
                }
            }
        }
    }

    private void sweepWorkspace(String catalog, int workspaceId) {
        try {
            backfillWorkspace(catalog, workspaceId);
        } catch (RuntimeException exception) {
            log.error(
                "Canonical identity backfill failed for workspace {}; the sweep reruns on the "
                    + "next start and duplicate detection stays degraded for it until then",
                workspaceId,
                exception);
        }
    }

    private void backfillWorkspace(String catalog, int workspaceId) {
        BackfillSummary summary = new BackfillSummary();
        int afterPersonId = 0;
        while (true) {
            IdentityBackfillBatch batch =
                backfillTransaction.backfillPersonPage(catalog, workspaceId, afterPersonId, PAGE_SIZE);
            summary.add(batch);
            if (batch.recordsScanned() == 0) {
                break;
            }
            if (batch.lastRecordId() <= afterPersonId) {
                throw new IllegalStateException("Canonical identity person cursor did not advance");
            }
            afterPersonId = batch.lastRecordId();
            if (batch.recordsScanned() < PAGE_SIZE) {
                break;
            }
        }
        int afterCompanyId = 0;
        while (true) {
            IdentityBackfillBatch batch =
                backfillTransaction.backfillCompanyPage(catalog, workspaceId, afterCompanyId, PAGE_SIZE);
            summary.add(batch);
            if (batch.recordsScanned() == 0) {
                break;
            }
            if (batch.lastRecordId() <= afterCompanyId) {
                throw new IllegalStateException("Canonical identity company cursor did not advance");
            }
            afterCompanyId = batch.lastRecordId();
            if (batch.recordsScanned() < PAGE_SIZE) {
                break;
            }
        }
        int collisionMemberships =
            backfillTransaction.rebuildCollisionReport(catalog, workspaceId);
        log.info(
            "Canonical identity backfill completed workspace {}: scanned={}, created={}, existing={}, "
                + "invalidEmail={}, invalidPhone={}, invalidDomain={}, skippedWrites={}, collisions={}",
            workspaceId,
            summary.recordsScanned,
            summary.identitiesCreated,
            summary.identitiesAlreadyPresent,
            summary.invalidEmails,
            summary.invalidPhones,
            summary.invalidDomains,
            summary.skippedWrites,
            collisionMemberships);
    }

    private void warnSkipped(int workspaceId, ServiceUnavailableException exception) {
        log.warn(
            "Canonical identity backfill skipped unservable workspace {}: {}",
            workspaceId,
            exception.getMessage());
    }

    private static final class BackfillSummary {
        private long recordsScanned;
        private long identitiesCreated;
        private long identitiesAlreadyPresent;
        private long invalidEmails;
        private long invalidPhones;
        private long invalidDomains;
        private long skippedWrites;

        private void add(IdentityBackfillBatch batch) {
            recordsScanned = Math.addExact(recordsScanned, batch.recordsScanned());
            identitiesCreated = Math.addExact(identitiesCreated, batch.identitiesCreated());
            identitiesAlreadyPresent =
                Math.addExact(identitiesAlreadyPresent, batch.identitiesAlreadyPresent());
            invalidEmails = Math.addExact(invalidEmails, batch.invalidEmails());
            invalidPhones = Math.addExact(invalidPhones, batch.invalidPhones());
            invalidDomains = Math.addExact(invalidDomains, batch.invalidDomains());
            skippedWrites = Math.addExact(skippedWrites, batch.skippedWrites());
        }
    }
}
