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
    public ObjectDeletionTombstone enqueueTenant(
            int workspaceId,
            String objectKey,
            int deletePassesRemaining,
            LocalDateTime nextAttemptAt) {
        String validKey = ObjectStorageKey.requireValid(objectKey);
        tenantQueueMapper.enqueue(
            workspaceId,
            validKey,
            deletePassesRemaining,
            nextAttemptAt);
        return tombstone(tenantQueueMapper.lockByKey(workspaceId, validKey), validKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ObjectDeletionTombstone enqueueUser(
            String objectKey,
            int deletePassesRemaining,
            LocalDateTime nextAttemptAt) {
        String validKey = ObjectStorageKey.requireValid(objectKey);
        userQueueMapper.enqueue(
            validKey,
            deletePassesRemaining,
            nextAttemptAt);
        return tombstone(userQueueMapper.lockByKey(validKey), validKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processTenant(
            int workspaceId,
            String objectKey,
            LocalDateTime now,
            LocalDateTime retryAt) {
        String validKey = ObjectStorageKey.requireValid(objectKey);
        ObjectDeletionTask task = tenantQueueMapper.lockDueByKey(workspaceId, validKey, now);
        if (task != null) {
            deleteTenant(task, retryAt);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processUser(String objectKey, LocalDateTime now, LocalDateTime retryAt) {
        String validKey = ObjectStorageKey.requireValid(objectKey);
        ObjectDeletionTask task = userQueueMapper.lockDueByKey(validKey, now);
        if (task != null) {
            deleteUser(task, retryAt);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryTenant(
            ObjectDeletionTask selected,
            LocalDateTime now,
            LocalDateTime retryAt) {
        ObjectDeletionTask task = tenantQueueMapper.lockDueByIdentity(
            selected.workspaceId(), selected.id(), selected.objectKey(), now);
        if (task == null) {
            return;
        }
        deleteTenant(task, retryAt);
    }

    private void deleteTenant(ObjectDeletionTask task, LocalDateTime retryAt) {
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
    public void retryUser(
            ObjectDeletionTask selected,
            LocalDateTime now,
            LocalDateTime retryAt) {
        ObjectDeletionTask task = userQueueMapper.lockDueByIdentity(
            selected.id(), selected.objectKey(), now);
        if (task == null) {
            return;
        }
        deleteUser(task, retryAt);
    }

    private void deleteUser(ObjectDeletionTask task, LocalDateTime retryAt) {
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

    private static ObjectDeletionTombstone tombstone(
            ObjectDeletionTask task,
            String expectedKey) {
        if (task == null || !expectedKey.equals(task.objectKey())) {
            throw new IllegalStateException("Object-deletion tombstone could not be reloaded");
        }
        return new ObjectDeletionTombstone(task.id(), task.objectKey());
    }
}
