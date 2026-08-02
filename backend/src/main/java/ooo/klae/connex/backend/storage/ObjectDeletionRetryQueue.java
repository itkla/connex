package ooo.klae.connex.backend.storage;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.ObjectDeletionQueueMapper;
import ooo.klae.connex.backend.mappers.UserObjectDeletionQueueMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunDetail;
import ooo.klae.connex.backend.observability.JobRunRecorder.JobRunStatus;
import ooo.klae.connex.backend.services.PlacementRegistry;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Durable, catalog-aware reconciliation for private object deletions.
 */
@Component
@RequiredArgsConstructor
public class ObjectDeletionRetryQueue {
    private static final Logger log = LoggerFactory.getLogger(ObjectDeletionRetryQueue.class);
    private static final String DEFAULT_CATALOG_CURSOR = "(default)";

    private final ObjectStorageProperties properties;
    private final ObjectDeletionQueueMapper tenantQueueMapper;
    private final UserObjectDeletionQueueMapper userQueueMapper;
    private final ObjectDeletionTransactionExecutor transactionExecutor;
    private final PlacementRegistry placementRegistry;
    private final TenantWorkScope tenantWorkScope;
    private final Clock clock;
    private final JobRunRecorder jobRunRecorder;
    private final Map<String, Integer> tenantCatalogCursors = new ConcurrentHashMap<>();
    private final ExecutorService retryExecutor = Executors.newFixedThreadPool(
        2,
        Thread.ofPlatform().daemon().name("object-deletion-retry-", 0).factory());
    private final AtomicBoolean userRetryRunning = new AtomicBoolean();
    private final AtomicBoolean tenantRetryRunning = new AtomicBoolean();

    public void enqueueTenantInCurrentTransaction(int workspaceId, String key) {
        tenantQueueMapper.enqueue(workspaceId, ObjectStorageKey.requireValid(key), 1, now());
        warnTenantBacklog(workspaceId);
    }

    public void enqueueUserInCurrentTransaction(String key) {
        userQueueMapper.enqueue(ObjectStorageKey.requireValid(key), 1, now());
        warnUserBacklog();
    }

    public void lockTenantInCurrentTransaction(
            int workspaceId,
            ObjectDeletionTombstone tombstone) {
        ObjectDeletionTask locked = tenantQueueMapper.lockByIdentity(
            workspaceId, tombstone.id(), tombstone.objectKey());
        requireSameTombstone(locked, tombstone);
    }

    public void lockUserInCurrentTransaction(ObjectDeletionTombstone tombstone) {
        ObjectDeletionTask locked = userQueueMapper.lockByIdentity(
            tombstone.id(), tombstone.objectKey());
        requireSameTombstone(locked, tombstone);
    }

    public void cancelTenantInCurrentTransaction(
            int workspaceId,
            ObjectDeletionTombstone tombstone) {
        if (tenantQueueMapper.deleteByIdentity(
                workspaceId, tombstone.id(), tombstone.objectKey()) != 1) {
            throw new IllegalStateException("Prepared tenant object cleanup changed before cancellation");
        }
    }

    public void cancelUserInCurrentTransaction(ObjectDeletionTombstone tombstone) {
        if (userQueueMapper.deleteByIdentity(tombstone.id(), tombstone.objectKey()) != 1) {
            throw new IllegalStateException("Prepared user object cleanup changed before cancellation");
        }
    }

    public void requireTenantWriteAllowed(int workspaceId) {
        if (tenantQueueMapper.countPendingAmbiguousWrites(workspaceId)
                >= properties.getMaxPendingTenantAmbiguousWriteCleanups()) {
            throw new ServiceUnavailableException(
                "Private object storage cleanup is degraded; retry after the backlog recovers");
        }
    }

    public ObjectDeletionTombstone prepareTenantWrite(int workspaceId, String key) {
        String validKey = ObjectStorageKey.requireValid(key);
        try {
            return tenantWorkScope.inWorkspace(workspaceId, () -> {
                ObjectDeletionTombstone tombstone = transactionExecutor.enqueueTenant(
                    workspaceId, validKey, 2, ambiguousWriteCleanupAt());
                warnTenantBacklog(workspaceId);
                return tombstone;
            });
        } catch (RuntimeException exception) {
            throw new ServiceUnavailableException(
                "Private object cleanup could not be prepared safely");
        }
    }

    public ObjectDeletionTombstone prepareUserWrite(String key) {
        String validKey = ObjectStorageKey.requireValid(key);
        try {
            return tenantWorkScope.unrouted(() -> {
                ObjectDeletionTombstone tombstone = transactionExecutor.enqueueUser(
                    validKey, 2, ambiguousWriteCleanupAt());
                warnUserBacklog();
                return tombstone;
            });
        } catch (RuntimeException exception) {
            throw new ServiceUnavailableException(
                "Private object cleanup could not be prepared safely");
        }
    }

    public void enqueueRollbackTombstoneTenant(int workspaceId, String key) {
        String validKey = ObjectStorageKey.requireValid(key);
        try {
            tenantWorkScope.inLifecycleWorkspace(workspaceId, () -> {
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
            tenantWorkScope.inLifecycleWorkspace(workspaceId,
                () -> {
                    LocalDateTime current = now();
                    transactionExecutor.processTenant(
                        workspaceId, validKey, current);
                });
        } catch (RuntimeException exception) {
            log.warn("Deferred tenant object deletion remains queued for workspace {}", workspaceId);
        }
    }

    /**
     * Processes one tenant deletion using the lifecycle caller's already
     * validated catalog route and propagates failures for terminal verification.
     */
    public void processTenantInLifecycleRoute(int workspaceId, String key) {
        transactionExecutor.processTenant(
            workspaceId,
            ObjectStorageKey.requireValid(key),
            now());
    }

    public void processUser(String key) {
        String validKey = ObjectStorageKey.requireValid(key);
        try {
            tenantWorkScope.unrouted(() -> {
                LocalDateTime current = now();
                transactionExecutor.processUser(validKey, current);
                return null;
            });
        } catch (RuntimeException exception) {
            log.warn("Deferred user object deletion remains queued");
        }
    }

    @Scheduled(
        fixedDelayString = "${connex.object-storage.delete-retry-delay-ms:60000}",
        initialDelayString = "${connex.object-storage.delete-retry-delay-ms:60000}")
    public void scheduleRetryPending() {
        submitRetry(userRetryRunning, this::retryUserCatalog);
        submitRetry(tenantRetryRunning, this::retryTenantCatalogs);
    }

    /** Runs one synchronous control-plane and tenant-plane retry sweep. */
    public void retryPending() {
        retryUserCatalog();
        retryTenantCatalogs();
    }

    private void retryTenantCatalogs() {
        for (String catalog : placementRegistry.activeCatalogs()) {
            try {
                tenantWorkScope.withCatalog(catalog, () -> {
                    retryTenantCatalogRaw(catalog);
                    return null;
                });
            } catch (RuntimeException exception) {
                log.warn("Private object deletion sweep failed for catalog {}",
                    catalog == null ? "(default)" : catalog);
            }
        }
    }

    private void submitRetry(AtomicBoolean running, Runnable retry) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            retryExecutor.execute(() -> {
                try {
                    retry.run();
                } finally {
                    running.set(false);
                }
            });
        } catch (RejectedExecutionException exception) {
            running.set(false);
            log.warn("Private object deletion sweep could not be scheduled");
        }
    }

    @PreDestroy
    void shutdownRetryExecutor() {
        retryExecutor.shutdownNow();
    }

    private void retryUserCatalog() {
        JobRunDetail detail = JobRunDetail.started(clock);
        tenantWorkScope.unrouted(() -> {
            try {
                List<ObjectDeletionTask> tasks = userQueueMapper.findDue(
                    now(), properties.getDeleteRetryBatchSize());
                boolean anyTaskFailed = false;
                for (ObjectDeletionTask task : tasks) {
                    try {
                        LocalDateTime current = now();
                        transactionExecutor.retryUser(task, current);
                    } catch (RuntimeException exception) {
                        anyTaskFailed = true;
                        log.warn("User object deletion task could not be finalized");
                    }
                }
                if (anyTaskFailed) {
                    record(null, JobRunStatus.FAILED,
                        new JobRunDetail(detail.startedAt(), Map.of("phase", "user_catalog")));
                } else if (!tasks.isEmpty()) {
                    record(null, JobRunStatus.SUCCEEDED,
                        new JobRunDetail(detail.startedAt(), Map.of("phase", "user_catalog")));
                }
            } catch (RuntimeException exception) {
                record(null, JobRunStatus.FAILED,
                    new JobRunDetail(detail.startedAt(), Map.of("phase", "user_catalog")));
                log.warn("Private user object deletion sweep failed");
            }
            return null;
        });
    }

    private void retryTenantCatalogRaw(String catalog) {
        LocalDateTime current = now();
        int remaining = properties.getDeleteRetryBatchSize();
        String cursorKey = catalog == null ? DEFAULT_CATALOG_CURSOR : catalog;
        int afterWorkspaceId = tenantCatalogCursors.getOrDefault(cursorKey, 0);
        List<Integer> workspaceIds = tenantQueueMapper.workspaceIdsWithDueTasks(
            current, afterWorkspaceId, remaining);
        for (int index = 0; index < workspaceIds.size(); index += 1) {
            if (remaining <= 0) {
                return;
            }
            int workspaceId = workspaceIds.get(index);
            int workspacesRemaining = workspaceIds.size() - index;
            int workspaceLimit = Math.max(1, remaining / workspacesRemaining);
            JobRunDetail detail = JobRunDetail.started(clock);
            try {
                List<ObjectDeletionTask> tasks = tenantQueueMapper.findDue(
                    workspaceId, current, workspaceLimit);
                int failedCount = 0;
                for (ObjectDeletionTask task : tasks) {
                    try {
                        LocalDateTime attemptAt = now();
                        transactionExecutor.retryTenant(task, attemptAt);
                    } catch (RuntimeException exception) {
                        failedCount++;
                        log.warn("Tenant object deletion task could not be finalized for workspace {}",
                            task.workspaceId());
                    }
                }
                record(workspaceId, JobRunStatus.SUCCEEDED,
                    new JobRunDetail(detail.startedAt(), Map.of(
                        "attemptedCount", tasks.size(),
                        "failedCount", failedCount)));
                remaining -= tasks.size();
                tenantCatalogCursors.put(cursorKey, workspaceId);
            } catch (RuntimeException exception) {
                record(workspaceId, JobRunStatus.FAILED,
                    new JobRunDetail(detail.startedAt(), Map.of("phase", "workspace_retry")));
                log.warn(
                    "Tenant object deletion workspace sweep failed for workspace {}",
                    workspaceId);
            }
        }
    }

    private void record(Integer workspaceId, JobRunStatus status, JobRunDetail detail) {
        try {
            jobRunRecorder.record(
                JobRunRecorder.OBJECT_DELETION_RETRY,
                workspaceId,
                status,
                detail);
        } catch (RuntimeException exception) {
            log.warn(
                "Job run recording failed jobName={} exceptionClass={}",
                JobRunRecorder.OBJECT_DELETION_RETRY,
                exception.getClass().getSimpleName());
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

    private LocalDateTime ambiguousWriteCleanupAt() {
        return now().plusNanos(properties.getAmbiguousWriteCleanupDelayMs() * 1_000_000L);
    }

    private static void requireSameTombstone(
            ObjectDeletionTask locked,
            ObjectDeletionTombstone expected) {
        if (locked == null
                || locked.id() != expected.id()
                || !locked.objectKey().equals(expected.objectKey())) {
            throw new ServiceUnavailableException(
                "Prepared private object cleanup changed before the write could start");
        }
    }
}
