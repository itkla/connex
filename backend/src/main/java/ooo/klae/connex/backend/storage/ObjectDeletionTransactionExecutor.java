package ooo.klae.connex.backend.storage;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.ObjectDeletionQueueMapper;
import ooo.klae.connex.backend.mappers.UserObjectDeletionQueueMapper;

/**
 * Isolates durable object-deletion mutations from completed or failed caller transactions.
 */
@Component
@RequiredArgsConstructor
public class ObjectDeletionTransactionExecutor {
    private final ObjectStorage objectStorage;
    private final ObjectDeletionQueueMapper tenantQueueMapper;
    private final UserObjectDeletionQueueMapper userQueueMapper;
    private final WorkspaceObjectStorageQuotaService quotaService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueTenant(
            int workspaceId,
            String objectKey,
            int deletePassesRemaining,
            LocalDateTime nextAttemptAt) {
        tenantQueueMapper.enqueue(
            workspaceId,
            ObjectStorageKey.requireValid(objectKey),
            deletePassesRemaining,
            nextAttemptAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueUser(
            String objectKey,
            int deletePassesRemaining,
            LocalDateTime nextAttemptAt) {
        userQueueMapper.enqueue(
            ObjectStorageKey.requireValid(objectKey),
            deletePassesRemaining,
            nextAttemptAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processTenant(
            int workspaceId,
            String objectKey,
            LocalDateTime retryAt) {
        String validKey = ObjectStorageKey.requireValid(objectKey);
        try {
            objectStorage.delete(validKey);
            quotaService.release(workspaceId, validKey);
            tenantQueueMapper.deleteByKey(workspaceId, validKey);
        } catch (ObjectStorageException exception) {
            tenantQueueMapper.rescheduleByKey(workspaceId, validKey, retryAt);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processUser(String objectKey, LocalDateTime retryAt) {
        String validKey = ObjectStorageKey.requireValid(objectKey);
        try {
            objectStorage.delete(validKey);
            userQueueMapper.deleteByKey(validKey);
        } catch (ObjectStorageException exception) {
            userQueueMapper.rescheduleByKey(validKey, retryAt);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryTenant(ObjectDeletionTask task, LocalDateTime retryAt) {
        try {
            objectStorage.delete(task.objectKey());
            if (task.deletePassesRemaining() > 1) {
                tenantQueueMapper.confirmDeletePass(task.workspaceId(), task.id(), retryAt);
                return;
            }
            quotaService.release(task.workspaceId(), task.objectKey());
            tenantQueueMapper.deleteById(task.workspaceId(), task.id());
        } catch (ObjectStorageException exception) {
            tenantQueueMapper.reschedule(task.workspaceId(), task.id(), retryAt);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryUser(ObjectDeletionTask task, LocalDateTime retryAt) {
        try {
            objectStorage.delete(task.objectKey());
            if (task.deletePassesRemaining() > 1) {
                userQueueMapper.confirmDeletePass(task.id(), retryAt);
                return;
            }
            userQueueMapper.deleteById(task.id());
        } catch (ObjectStorageException exception) {
            userQueueMapper.reschedule(task.id(), retryAt);
        }
    }
}
