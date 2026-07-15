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

    private final ObjectStorage objectStorage;
    private final ObjectStorageProperties properties;
    private final ObjectDeletionQueueMapper tenantQueueMapper;
    private final UserObjectDeletionQueueMapper userQueueMapper;
    private final ObjectDeletionTransactionExecutor transactionExecutor;
    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final Clock clock;

    public void enqueueTenantInCurrentTransaction(int workspaceId, String key) {
        tenantQueueMapper.enqueue(workspaceId, ObjectStorageKey.requireValid(key), now());
        warnTenantBacklog(workspaceId);
    }

    public void enqueueUserInCurrentTransaction(String key) {
        userQueueMapper.enqueue(ObjectStorageKey.requireValid(key), now());
        warnUserBacklog();
    }

    public void enqueueAndProcessTenant(int workspaceId, String key) {
        String validKey = ObjectStorageKey.requireValid(key);
        try {
            tenantWorkScope.inWorkspace(workspaceId, () -> {
                try {
                    transactionExecutor.enqueueTenant(workspaceId, validKey, now());
                    transactionExecutor.processTenant(workspaceId, validKey, retryAt());
                } catch (RuntimeException exception) {
                    log.error("Could not persist a tenant object deletion task; attempting direct cleanup");
                    deleteTenantWithoutQueue(workspaceId, validKey);
                }
            });
        } catch (RuntimeException exception) {
            log.error("Could not route tenant object cleanup; operator reconciliation is required");
            deleteWithoutQueue(validKey);
        }
    }

    public void enqueueAndProcessUser(String key) {
        String validKey = ObjectStorageKey.requireValid(key);
        try {
            tenantWorkScope.unrouted(() -> {
                try {
                    transactionExecutor.enqueueUser(validKey, now());
                    transactionExecutor.processUser(validKey, retryAt());
                } catch (RuntimeException exception) {
                    log.error("Could not persist a user object deletion task; attempting direct cleanup");
                    deleteWithoutQueue(validKey);
                }
                return null;
            });
        } catch (RuntimeException exception) {
            log.error("Could not route user object cleanup; operator reconciliation is required");
            deleteWithoutQueue(validKey);
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

    private void deleteTenantWithoutQueue(int workspaceId, String key) {
        try {
            objectStorage.delete(key);
        } catch (ObjectStorageException exception) {
            log.error("Direct private object cleanup failed; operator reconciliation is required");
            return;
        }
        try {
            transactionExecutor.releaseTenantQuota(workspaceId, key);
        } catch (RuntimeException exception) {
            log.error("Direct private object cleanup could not release quota; operator reconciliation is required");
        }
    }

    private void deleteWithoutQueue(String key) {
        try {
            objectStorage.delete(key);
        } catch (ObjectStorageException exception) {
            log.error("Direct private object cleanup failed; operator reconciliation is required");
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
}
