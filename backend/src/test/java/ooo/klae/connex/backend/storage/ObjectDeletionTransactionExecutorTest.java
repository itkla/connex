package ooo.klae.connex.backend.storage;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.mappers.ObjectDeletionQueueMapper;
import ooo.klae.connex.backend.mappers.UserObjectDeletionQueueMapper;

@ExtendWith(MockitoExtension.class)
class ObjectDeletionTransactionExecutorTest {
    @Mock ObjectStorage objectStorage;
    @Mock ObjectDeletionQueueMapper tenantQueueMapper;
    @Mock UserObjectDeletionQueueMapper userQueueMapper;
    @Mock WorkspaceObjectStorageQuotaService quotaService;

    private ObjectDeletionTransactionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ObjectDeletionTransactionExecutor(
            objectStorage, tenantQueueMapper, userQueueMapper, quotaService);
    }

    @Test
    void successfulTenantDeletionReleasesExactQuotaAndRemovesTask() {
        ObjectDeletionTask task = new ObjectDeletionTask(
            11, 7, "workspaces/7/attachments/object.pdf", 2, 1);

        executor.retryTenant(task, LocalDateTime.of(2026, 7, 14, 12, 1));

        verify(objectStorage).delete(task.objectKey());
        verify(quotaService).release(7, task.objectKey());
        verify(tenantQueueMapper).deleteById(7, 11);
    }

    @Test
    void failedPhysicalDeletionRemainsQueuedAndCharged() {
        ObjectDeletionTask task = new ObjectDeletionTask(
            11, 7, "workspaces/7/attachments/object.pdf", 2, 1);
        LocalDateTime retryAt = LocalDateTime.of(2026, 7, 14, 12, 1);
        org.mockito.Mockito.doThrow(new ObjectStorageException("unavailable"))
            .when(objectStorage).delete(task.objectKey());

        executor.retryTenant(task, retryAt);

        verify(tenantQueueMapper).reschedule(7, 11, retryAt);
        verifyNoInteractions(quotaService);
        verify(tenantQueueMapper, never()).deleteById(7, 11);
    }

    @Test
    void missingUsageStillAllowsIdempotentQueueFinalization() {
        String key = "workspaces/7/attachments/object.pdf";

        executor.processTenant(7, key, LocalDateTime.of(2026, 7, 14, 12, 1));

        verify(quotaService).release(7, key);
        verify(tenantQueueMapper).deleteByKey(7, key);
    }

    @Test
    void ambiguousWriteTombstoneRequiresASecondSuccessfulDeletePass() {
        ObjectDeletionTask task = new ObjectDeletionTask(
            11, 7, "workspaces/7/attachments/object.pdf", 1, 2);
        LocalDateTime retryAt = LocalDateTime.of(2026, 7, 14, 12, 1);

        executor.retryTenant(task, retryAt);

        verify(objectStorage).delete(task.objectKey());
        verify(tenantQueueMapper).confirmDeletePass(7, 11, retryAt);
        verifyNoInteractions(quotaService);
        verify(tenantQueueMapper, never()).deleteById(7, 11);
    }

    @Test
    void ambiguousUserWriteTombstoneRequiresASecondSuccessfulDeletePass() {
        ObjectDeletionTask task = new ObjectDeletionTask(
            12, 0, "users/9/profile-images/object.png", 1, 2);
        LocalDateTime retryAt = LocalDateTime.of(2026, 7, 14, 12, 1);

        executor.retryUser(task, retryAt);

        verify(objectStorage).delete(task.objectKey());
        verify(userQueueMapper).confirmDeletePass(12, retryAt);
        verify(userQueueMapper, never()).deleteById(12);
    }
}
