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

    private NotificationStateVersionService service;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        service = new NotificationStateVersionService(notificationMapper);
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

        InOrder order = inOrder(notificationMapper);
        order.verify(notificationMapper).bumpStateVersions(List.of(9));
        order.verify(notificationMapper).getStateVersion(9);
        order.verify(notificationMapper).bumpStateVersions(List.of(2));
        assertEquals(17L, version);
        synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
    }

    private static TransactionSynchronization onlySynchronization() {
        List<TransactionSynchronization> synchronizations =
            TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        return synchronizations.getFirst();
    }
}
