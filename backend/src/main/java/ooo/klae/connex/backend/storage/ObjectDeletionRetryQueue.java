package ooo.klae.connex.backend.storage;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.ObjectDeletionQueueMapper;
import ooo.klae.connex.backend.mappers.UserObjectDeletionQueueMapper;
import ooo.klae.connex.backend.services.PlacementRegistry;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Durable, catalog-aware reconciliation for private object deletions.
 */
@Component
@RequiredArgsConstructor
public class ObjectDeletionRetryQueue {
    private static final Logger log = LoggerFactory.getLogger(ObjectDeletionRetryQueue.class);

    private final ObjectStorageProperties properties;
    private final ObjectDeletionQueueMapper tenantQueueMapper;
    private final UserObjectDeletionQueueMapper userQueueMapper;
    private final ObjectDeletionTransactionExecutor transactionExecutor;
    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final Clock clock;

    public void enqueueTenantInCurrentTransaction(int workspaceId, String key) {
        tenantQueueMapper.enqueue(workspaceId, ObjectStorageKey.requireValid(key), 1, now());
        warnTenantBacklog(workspaceId);
    }

    public void enqueueUserInCurrentTransaction(String key) {
        userQueueMapper.enqueue(ObjectStorageKey.requireValid(key), 1, now());
        warnUserBacklog();
    }

    public void cancelTenantInCurrentTransaction(int workspaceId, String key) {
        tenantQueueMapper.deleteByKey(workspaceId, ObjectStorageKey.requireValid(key));
    }

    public void cancelUserInCurrentTransaction(String key) {
        userQueueMapper.deleteByKey(ObjectStorageKey.requireValid(key));
    }

    public void requireTenantWriteAllowed(int workspaceId) {
        if (tenantQueueMapper.countPendingAmbiguousWrites(workspaceId)
                >= properties.getMaxPendingTenantAmbiguousWriteCleanups()) {
            throw new ServiceUnavailableException(
                "Private object storage cleanup is degraded; retry after the backlog recovers");
        }
    }

    public void prepareTenantWrite(int workspaceId, String key) {
        String validKey = ObjectStorageKey.requireValid(key);
        try {
            tenantWorkScope.inWorkspace(workspaceId, () -> {
                transactionExecutor.enqueueTenant(
                    workspaceId, validKey, 2, ambiguousWriteCleanupAt());
                warnTenantBacklog(workspaceId);
            });
        } catch (RuntimeException exception) {
            throw new ServiceUnavailableException(
                "Private object cleanup could not be prepared safely");
        }
    }

    public void prepareUserWrite(String key) {
        String validKey = ObjectStorageKey.requireValid(key);
        try {
            tenantWorkScope.unrouted(() -> {
                transactionExecutor.enqueueUser(validKey, 2, ambiguousWriteCleanupAt());
                warnUserBacklog();
                return null;
            });
        } catch (RuntimeException exception) {
            throw new ServiceUnavailableException(
                "Private object cleanup could not be prepared safely");
        }
    }

    public void enqueueRollbackTombstoneTenant(int workspaceId, String key) {
        String validKey = ObjectStorageKey.requireValid(key);
        try {
            tenantWorkScope.inWorkspace(workspaceId, () -> {
                try {
                    transactionExecutor.enqueueTenant(
                        workspaceId, validKey, 2, ambiguousWriteCleanupAt());
                    warnTenantBacklog(workspaceId);
                } catch (RuntimeException exception) {
                    log.error("Could not persist an ambiguous tenant object cleanup tombstone; operator reconciliation is required");
                }
            });
        } catch (RuntimeException exception) {
            log.error("Could not route ambiguous tenant object cleanup; operator reconciliation is required");
        }
    }

    public void enqueueRollbackTombstoneUser(String key) {
        String validKey = ObjectStorageKey.requireValid(key);
        try {
            tenantWorkScope.unrouted(() -> {
                try {
                    transactionExecutor.enqueueUser(validKey, 2, ambiguousWriteCleanupAt());
                    warnUserBacklog();
                } catch (RuntimeException exception) {
                    log.error("Could not persist an ambiguous user object cleanup tombstone; operator reconciliation is required");
                }
                return null;
            });
        } catch (RuntimeException exception) {
            log.error("Could not route ambiguous user object cleanup; operator reconciliation is required");
        }
    }

    public void processTenant(int workspaceId, String key) {
        String validKey = ObjectStorageKey.requireValid(key);
        try {
            tenantWorkScope.inWorkspace(workspaceId,
                () -> transactionExecutor.processTenant(workspaceId, validKey, retryAt()));
        } catch (RuntimeException exception) {
            log.warn("Deferred tenant object deletion remains queued for workspace {}", workspaceId);
        }
    }

    public void processUser(String key) {
        String validKey = ObjectStorageKey.requireValid(key);
        try {
            tenantWorkScope.unrouted(() -> {
                transactionExecutor.processUser(validKey, retryAt());
                return null;
            });
        } catch (RuntimeException exception) {
            log.warn("Deferred user object deletion remains queued");
        }
    }

    @Scheduled(
        fixedDelayString = "${connex.object-storage.delete-retry-delay-ms:60000}",
        initialDelayString = "${connex.object-storage.delete-retry-delay-ms:60000}")
    public void retryPending() {
        retryUserCatalog();
        for (String catalog : placementRegistry.activeCatalogs()) {
            try {
                tenantWorkScope.withCatalog(catalog, () -> {
                    retryTenantCatalogRaw();
                    return null;
                });
            } catch (RuntimeException exception) {
                log.warn("Private object deletion sweep failed for catalog {}",
                    catalog == null ? "(default)" : catalog);
            }
        }
    }

    private void retryUserCatalog() {
        try {
            tenantWorkScope.unrouted(() -> {
                List<ObjectDeletionTask> tasks = userQueueMapper.findDue(
                    now(), properties.getDeleteRetryBatchSize());
                for (ObjectDeletionTask task : tasks) {
                    try {
                        transactionExecutor.retryUser(task, retryAt());
                    } catch (RuntimeException exception) {
                        log.warn("User object deletion task could not be finalized");
                    }
                }
                return null;
            });
        } catch (RuntimeException exception) {
            log.warn("Private user object deletion sweep failed");
        }
    }

    private void retryTenantCatalogRaw() {
        LocalDateTime current = now();
        int remaining = properties.getDeleteRetryBatchSize();
        List<Integer> workspaceIds = tenantQueueMapper.workspaceIdsWithDueTasks(current, remaining);
        for (int workspaceId : workspaceIds) {
            if (remaining <= 0) {
                return;
            }
            List<ObjectDeletionTask> tasks = tenantQueueMapper.findDue(
                workspaceId, current, remaining);
            for (ObjectDeletionTask task : tasks) {
                try {
                    transactionExecutor.retryTenant(task, retryAt());
                } catch (RuntimeException exception) {
                    log.warn("Tenant object deletion task could not be finalized for workspace {}",
                        task.workspaceId());
                }
            }
            remaining -= tasks.size();
        }
    }

    private void warnTenantBacklog(int workspaceId) {
        long pending = tenantQueueMapper.countPending(workspaceId);
        if (pending > properties.getDeleteRetryWarningEntries()) {
            log.error("Private object deletion backlog exceeded its warning threshold for workspace {}",
                workspaceId);
        }
    }

    private void warnUserBacklog() {
        if (userQueueMapper.countPending() > properties.getDeleteRetryWarningEntries()) {
            log.error("Private user object deletion backlog exceeded its warning threshold");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private LocalDateTime retryAt() {
        return now().plusNanos(properties.getDeleteRetryDelayMs() * 1_000_000L);
    }

    private LocalDateTime ambiguousWriteCleanupAt() {
        return now().plusNanos(properties.getAmbiguousWriteCleanupDelayMs() * 1_000_000L);
    }
}
