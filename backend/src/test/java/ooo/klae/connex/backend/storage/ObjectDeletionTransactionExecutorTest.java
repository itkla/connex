package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.mappers.ObjectDeletionQueueMapper;
import ooo.klae.connex.backend.mappers.UserObjectDeletionQueueMapper;

@ExtendWith(MockitoExtension.class)
class ObjectDeletionTransactionExecutorTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 14, 12, 0);
    private static final LocalDateTime RETRY_AT = LocalDateTime.of(2026, 7, 14, 12, 1);

    @Mock ObjectStorage objectStorage;
    @Mock ObjectDeletionQueueMapper tenantQueueMapper;
    @Mock UserObjectDeletionQueueMapper userQueueMapper;
    @Mock WorkspaceObjectStorageQuotaService quotaService;

    private ObjectStorageProperties properties;
    private ObjectDeletionTransactionExecutor executor;

    @BeforeEach
    void setUp() {
        properties = new ObjectStorageProperties();
        executor = new ObjectDeletionTransactionExecutor(
            objectStorage,
            tenantQueueMapper,
            userQueueMapper,
            quotaService,
            properties,
            Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    @Test
    void tenantRetryLocksTheSelectedTombstoneThroughProviderAndQuotaMutations() {
        ObjectDeletionTask task = tenantTask(11, 1);
        when(tenantQueueMapper.lockDueByIdentity(7, 11, task.objectKey(), NOW))
            .thenReturn(task);

        executor.retryTenant(task, NOW);

        InOrder order = inOrder(tenantQueueMapper, objectStorage, quotaService);
        order.verify(tenantQueueMapper).lockDueByIdentity(7, 11, task.objectKey(), NOW);
        order.verify(objectStorage).delete(task.objectKey());
        order.verify(quotaService).release(7, task.objectKey());
        order.verify(tenantQueueMapper).deleteById(7, 11);
    }

    @Test
    void failedPhysicalDeletionRemainsQueuedAndCharged() {
        ObjectDeletionTask task = tenantTask(11, 1);
        when(tenantQueueMapper.lockDueByIdentity(7, 11, task.objectKey(), NOW))
            .thenReturn(task);
        org.mockito.Mockito.doThrow(new ObjectStorageException("unavailable"))
            .when(objectStorage).delete(task.objectKey());

        executor.retryTenant(task, NOW);

        verify(tenantQueueMapper).reschedule(7, 11, RETRY_AT);
        verifyNoInteractions(quotaService);
        verify(tenantQueueMapper, never()).deleteById(7, 11);
    }

    @Test
    void directProcessingReloadsAndLocksTheDueTombstone() {
        ObjectDeletionTask task = tenantTask(11, 1);
        when(tenantQueueMapper.lockDueByKey(7, task.objectKey(), NOW)).thenReturn(task);

        executor.processTenant(7, task.objectKey(), NOW);

        verify(objectStorage).delete(task.objectKey());
        verify(quotaService).release(7, task.objectKey());
        verify(tenantQueueMapper).deleteById(7, 11);
    }

    @Test
    void selectedRetryCannotDeleteAfterItsTombstoneWasCancelledOrReplaced() {
        ObjectDeletionTask stale = tenantTask(11, 1);
        when(tenantQueueMapper.lockDueByIdentity(7, 11, stale.objectKey(), NOW))
            .thenReturn(null);

        executor.retryTenant(stale, NOW);

        verifyNoInteractions(objectStorage, quotaService);
        verify(tenantQueueMapper, never()).deleteById(7, 11);
    }

    @Test
    void newlyDelayedTombstoneCannotBeDeletedByAPreviouslySelectedRetry() {
        ObjectDeletionTask selected = tenantTask(11, 1);
        when(tenantQueueMapper.lockDueByIdentity(7, 11, selected.objectKey(), NOW))
            .thenReturn(null);

        executor.retryTenant(selected, NOW);

        verifyNoInteractions(objectStorage, quotaService);
        verify(tenantQueueMapper, never()).reschedule(7, 11, RETRY_AT);
    }

    @Test
    void selectedUserRetryCannotDeleteAReplacementTombstone() {
        ObjectDeletionTask stale = new ObjectDeletionTask(
            12, 0, "users/9/profile-images/object.png", 1, 1);
        when(userQueueMapper.lockDueByIdentity(12, stale.objectKey(), NOW))
            .thenReturn(null);

        executor.retryUser(stale, NOW);

        verifyNoInteractions(objectStorage, quotaService);
        verify(userQueueMapper, never()).deleteById(12);
        verify(userQueueMapper, never()).reschedule(12, RETRY_AT);
    }

    @Test
    void ambiguousWriteTombstoneRequiresASecondSuccessfulDeletePass() {
        ObjectDeletionTask task = tenantTask(11, 2);
        when(tenantQueueMapper.lockDueByIdentity(7, 11, task.objectKey(), NOW))
            .thenReturn(task);

        executor.retryTenant(task, NOW);

        verify(objectStorage).delete(task.objectKey());
        verify(tenantQueueMapper).confirmDeletePass(7, 11, RETRY_AT);
        verifyNoInteractions(quotaService);
        verify(tenantQueueMapper, never()).deleteById(7, 11);
    }

    @Test
    void ambiguousUserWriteTombstoneRequiresASecondSuccessfulDeletePass() {
        ObjectDeletionTask task = new ObjectDeletionTask(
            12, 0, "users/9/profile-images/object.png", 1, 2);
        when(userQueueMapper.lockDueByIdentity(12, task.objectKey(), NOW)).thenReturn(task);

        executor.retryUser(task, NOW);

        verify(objectStorage).delete(task.objectKey());
        verify(userQueueMapper).confirmDeletePass(12, RETRY_AT);
        verify(userQueueMapper, never()).deleteById(12);
    }

    @Test
    void secondDeletePassIsDelayedFromProviderCompletion() {
        ObjectDeletionTask task = tenantTask(11, 2);
        AtomicReference<Instant> current = new AtomicReference<>(NOW.toInstant(ZoneOffset.UTC));
        Clock advancingClock = org.mockito.Mockito.mock(Clock.class);
        when(advancingClock.instant()).thenAnswer(invocation -> current.get());
        executor = new ObjectDeletionTransactionExecutor(
            objectStorage,
            tenantQueueMapper,
            userQueueMapper,
            quotaService,
            properties,
            advancingClock);
        when(tenantQueueMapper.lockDueByIdentity(7, 11, task.objectKey(), NOW))
            .thenReturn(task);
        org.mockito.Mockito.doAnswer(invocation -> {
            current.set(NOW.plusMinutes(5).toInstant(ZoneOffset.UTC));
            return null;
        }).when(objectStorage).delete(task.objectKey());

        executor.retryTenant(task, NOW);

        verify(tenantQueueMapper).confirmDeletePass(7, 11, NOW.plusMinutes(6));
    }

    @Test
    void enqueueReturnsTheLockedDatabaseIdentity() {
        String key = "workspaces/7/attachments/object.pdf";
        ObjectDeletionTask task = tenantTask(19, 2);
        when(tenantQueueMapper.lockByKey(7, key)).thenReturn(task);

        ObjectDeletionTombstone tombstone = executor.enqueueTenant(7, key, 2, RETRY_AT);

        assertEquals(new ObjectDeletionTombstone(19, key), tombstone);
    }

    private static ObjectDeletionTask tenantTask(long id, int passes) {
        return new ObjectDeletionTask(
            id, 7, "workspaces/7/attachments/object.pdf", 1, passes);
    }
}
