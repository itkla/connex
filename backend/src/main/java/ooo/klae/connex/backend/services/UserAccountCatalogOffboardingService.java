package ooo.klae.connex.backend.services;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProviders;
import ooo.klae.connex.backend.connectedaccounts.capture.ProviderCapturePurgeService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.mappers.AssignmentSweepMapper;
import ooo.klae.connex.backend.mappers.AssignmentSweepMapper.Cursor;
import ooo.klae.connex.backend.mappers.AssignmentSweepMapper.Kind;
import ooo.klae.connex.backend.mappers.AssignmentSweepMapper.Reference;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * Routes account-deletion guards and erasure through every distinct tenant catalog.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAccountCatalogOffboardingService {
    private final DepartureApprovalGuard departureApprovalGuard;
    private final PlacementRegistry placementRegistry;
    private final UserOffboardingService userOffboardingService;
    private final ProviderCapturePurgeService providerCapturePurgeService;
    private final TenantWorkScope tenantWorkScope;
    private final PlatformTransactionManager transactionManager;
    private final AssignmentSweepMapper assignmentSweepMapper;
    private final UserMapper userMapper;
    private final MeterRegistry meterRegistry;
    private final OwnershipOfferAccountCleanupService ownershipOfferCleanupService;
    private final Map<TraversalKey, Traversal> traversals = new HashMap<>();

    @Value("${connex.account.assignment-sweep.page-size:200}")
    private int pageSize = 200;

    /** Refuses account deletion while any catalog retains authored content or a sole named route. */
    public void assertNoAuthoredContent(int userId) {
        for (String catalog : placementRegistry.activeCatalogs()) {
            tenantWorkScope.withCatalog(
                catalog,
                () -> {
                    new TransactionTemplate(transactionManager).executeWithoutResult(
                        status -> {
                            userOffboardingService.assertNoAuthoredContent(userId);
                            departureApprovalGuard.assertCanErase(userId);
                        });
                    return null;
                });
        }
    }

    /** Erases user references once per distinct tenant catalog. */
    public void eraseReferences(int userId) {
        ownershipOfferCleanupService.eraseBeforeCatalogs(userId);
        for (String catalog : placementRegistry.activeCatalogs()) {
            tenantWorkScope.withCatalog(
                catalog,
                () -> {
                    new TransactionTemplate(transactionManager).executeWithoutResult(
                        status -> {
                            departureApprovalGuard.assertCanErase(userId);
                            providerCapturePurgeService.purgeAccountCatalog(
                                userId, ConnectedAccountProviders.GOOGLE);
                            providerCapturePurgeService.purgeAccountCatalog(
                                userId, ConnectedAccountProviders.MICROSOFT);
                            providerCapturePurgeService.clearAccountReferencesInCatalog(
                                userId);
                            userOffboardingService.assertNoAuthoredContent(userId);
                            userOffboardingService.eraseOrgDataReferences(userId);
                        });
                    return null;
                });
        }
    }

    /**
     * Drains post-cascade window writes after the account's control-plane deletion has committed.
     * Failed units remain pending for periodic recovery and never fail a completed deletion.
     *
     * @throws IllegalStateException when an ambient transaction could hide the committed deletion
     */
    public void sweepAssignmentsAfterDeletion(int userId) {
        requireNoTransaction();
        try {
            if (!existingIds(List.of(userId)).isEmpty()) {
                return;
            }
        } catch (RuntimeException failure) {
            reportFailure("immediate", "existence", null, failure);
            return;
        }
        sweepCatalogs("immediate", userId);
    }

    /**
     * Visits one bounded page per catalog and kind. Each traversal retains a fixed upper watermark,
     * advances past failed units, and wraps on an empty page, so new inserts cannot starve recovery.
     * Cursors are disposable: restarting safely rediscovers all surviving orphan references.
     */
    @Scheduled(
        fixedDelayString = "${connex.account.assignment-sweep.delay-ms:300000}",
        initialDelayString = "${connex.account.assignment-sweep.initial-delay-ms:300000}")
    public synchronized void sweepOrphanAssignments() {
        requireNoTransaction();
        sweepCatalogs("periodic", null);
    }

    private void sweepCatalogs(String mode, Integer userId) {
        List<String> catalogs;
        try {
            catalogs = tenantWorkScope.unroutedRead(placementRegistry::activeCatalogs);
        } catch (RuntimeException failure) {
            reportFailure(mode, "catalogs", null, failure);
            return;
        }
        for (String catalog : catalogs) {
            for (Kind kind : Kind.values()) {
                try {
                    sweepKind(mode, catalog, kind, userId);
                } catch (RuntimeException failure) {
                    reportFailure(mode, "page", catalog, failure);
                }
            }
        }
    }

    private void sweepKind(String mode, String catalog, Kind kind, Integer userId) {
        TraversalKey key = new TraversalKey(catalog, kind);
        Traversal traversal = userId == null ? traversals.get(key) : null;
        if (traversal == null) {
            Cursor watermark = tenantWorkScope.withCatalog(
                catalog, () -> assignmentSweepMapper.findWatermark(kind));
            if (watermark == null) {
                return;
            }
            traversal = new Traversal(Cursor.beginning(), watermark);
            if (userId == null) {
                traversals.put(key, traversal);
            }
        }
        while (true) {
            Traversal pageTraversal = traversal;
            List<Reference> page = tenantWorkScope.withCatalog(catalog, () ->
                assignmentSweepMapper.findReferencesAnywhere(
                    kind, userId, pageTraversal.cursor(), pageTraversal.watermark(), Math.max(1, pageSize)));
            if (page.isEmpty()) {
                if (userId == null) {
                    traversals.remove(key);
                }
                return;
            }
            Set<Integer> existing;
            try {
                existing = existingIds(page.stream().map(Reference::userId).distinct().toList());
            } catch (RuntimeException failure) {
                reportFailure(mode, "existence", catalog, failure);
                existing = Set.copyOf(page.stream().map(Reference::userId).toList());
            }
            for (Reference reference : page) {
                if (!existing.contains(reference.userId())) {
                    sweepRecord(mode, catalog, kind, reference);
                }
            }
            traversal = new Traversal(page.getLast().cursor(), traversal.watermark());
            if (userId == null) {
                traversals.put(key, traversal);
                return;
            }
        }
    }

    private Set<Integer> existingIds(List<Integer> ids) {
        return Set.copyOf(tenantWorkScope.unroutedRead(() -> userMapper.findExistingIds(ids)));
    }

    private void sweepRecord(String mode, String catalog, Kind kind, Reference reference) {
        try {
            tenantWorkScope.withCatalog(catalog, () -> {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                transaction.executeWithoutResult(status -> {
                    if (assignmentSweepMapper.lockParent(kind, reference) == null) {
                        return;
                    }
                    switch (kind) {
                        case COMPANY, DEAL -> assignmentSweepMapper.clearOwner(kind, reference);
                        case PERSON, LIFECYCLE_PASS -> {
                            assignmentSweepMapper.clearOwner(kind, reference);
                            assignmentSweepMapper.clearPersonPassOwners(reference);
                        }
                        case COLLABORATOR -> assignmentSweepMapper.removeCollaborator(reference);
                        case TASK -> assignmentSweepMapper.unassignTask(reference);
                        case INTERVENTION -> assignmentSweepMapper.clearInterventionOwner(reference);
                        case CAMPAIGN -> assignmentSweepMapper.clearCampaignOwner(reference);
                    }
                });
                return null;
            });
        } catch (RuntimeException failure) {
            reportFailure(mode, "record", catalog, failure);
        }
    }

    private void reportFailure(String mode, String phase, String catalog, RuntimeException failure) {
        meterRegistry.counter("connex.account.assignment.sweep.failures", "mode", mode, "phase", phase)
            .increment();
        log.warn("Assignment sweep failed in catalog {}: {}", catalog, failure.getClass().getSimpleName());
    }

    private void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Assignment sweeps require committed control-plane state");
        }
    }

    private record TraversalKey(String catalog, Kind kind) {
    }

    private record Traversal(Cursor cursor, Cursor watermark) {
    }
}
