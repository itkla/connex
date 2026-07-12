package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.mappers.NotificationMapper;

@ExtendWith(MockitoExtension.class)
class NotificationStateVersionServiceTest {
    @Mock private NotificationMapper notificationMapper;
    @Mock private NotificationPushPublisher pushPublisher;

    private NotificationStateVersionService service;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        service = new NotificationStateVersionService(notificationMapper, pushPublisher);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void deferredBumpDeduplicatesRecipientsAndUsesStableLockOrder() {
        service.markChanged(9);
        service.markChanged(2);
        service.markChanged(9);

        verify(notificationMapper, never()).bumpStateVersions(List.of(2, 9));

        TransactionSynchronization synchronization = onlySynchronization();
        synchronization.beforeCommit(false);

        verify(notificationMapper).bumpStateVersions(List.of(2, 9));
        InOrder pushOrder = inOrder(pushPublisher);
        pushOrder.verify(pushPublisher).invalidated(2);
        pushOrder.verify(pushPublisher).invalidated(9);
        synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
    }

    @Test
    void immediateBumpIsNotRepeatedAtTransactionEnd() {
        service.markChanged(9);
        service.markChanged(2);
        when(notificationMapper.getStateVersion(9)).thenReturn(17L);

        long version = service.bumpNow(9);
        TransactionSynchronization synchronization = onlySynchronization();
        synchronization.beforeCommit(false);

        InOrder order = inOrder(notificationMapper, pushPublisher);
        order.verify(notificationMapper).bumpStateVersions(List.of(9));
        order.verify(notificationMapper).getStateVersion(9);
        order.verify(pushPublisher).invalidated(9);
        order.verify(notificationMapper).bumpStateVersions(List.of(2));
        order.verify(pushPublisher).invalidated(2);
        assertEquals(17L, version);
        synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
    }

    @Test
    void detailedPushSuppressesGenericInvalidationUnlessAnotherMutationRequiresIt() {
        service.markChangedWithDetailedPush(9);
        service.markChangedWithDetailedPush(2);
        service.markChanged(9);

        TransactionSynchronization synchronization = onlySynchronization();
        synchronization.beforeCommit(false);

        verify(notificationMapper).bumpStateVersions(List.of(2, 9));
        verify(pushPublisher).invalidated(9);
        verify(pushPublisher, never()).invalidated(2);
        synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
    }

    @Test
    void changeOutsideTransactionBumpsBeforePublishingInvalidation() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);

        service.markChanged(9);

        InOrder order = inOrder(notificationMapper, pushPublisher);
        order.verify(notificationMapper).bumpStateVersions(List.of(9));
        order.verify(pushPublisher).invalidated(9);
    }

    @Test
    void rolledBackChangePublishesNothing() {
        service.markChanged(9);

        onlySynchronization().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(notificationMapper, never()).bumpStateVersions(List.of(9));
        verify(pushPublisher, never()).invalidated(9);
    }

    private static TransactionSynchronization onlySynchronization() {
        List<TransactionSynchronization> synchronizations =
            TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        return synchronizations.getFirst();
    }
}
