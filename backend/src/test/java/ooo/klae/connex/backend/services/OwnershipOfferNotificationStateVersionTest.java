package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ooo.klae.connex.backend.mappers.OwnershipOfferMapper;
import ooo.klae.connex.backend.notifications.NotificationPushPublisher;

/** Keeps closure counter writes sorted, deduplicated, transactional and fail-closed. */
@ExtendWith(MockitoExtension.class)
class OwnershipOfferNotificationStateVersionTest {
    @Mock private OwnershipOfferMapper mapper;
    @Mock private NotificationPushPublisher push;
    private OwnershipOfferNotificationStateVersionService service;
    @BeforeEach void transaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        service = new OwnershipOfferNotificationStateVersionService(mapper, push);
    }
    @AfterEach void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        }
        TransactionSynchronizationManager.clear();
    }
    private TransactionSynchronization synchronization() {
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, callbacks.size()); return callbacks.getFirst();
    }
    @Test void versionsUseSortedDistinctRecipientsBeforePublishingInvalidation() {
        service.markChanged(9); service.markChanged(2); service.markChanged(9);
        verifyNoInteractions(mapper, push);
        when(mapper.bumpExistingNoticeStateVersion(2)).thenReturn(1);
        when(mapper.bumpExistingNoticeStateVersion(9)).thenReturn(1);
        synchronization().beforeCommit(false);
        var order = inOrder(mapper, push);
        order.verify(mapper).bumpExistingNoticeStateVersion(2);
        order.verify(mapper).bumpExistingNoticeStateVersion(9);
        order.verify(push).invalidated(2); order.verify(push).invalidated(9);
    }
    @Test void aMissingDeliveredStateRowRefusesCommitWithoutPublishing() {
        service.markChanged(2);
        assertThrows(IllegalStateException.class, () -> synchronization().beforeCommit(false));
        verifyNoInteractions(push);
    }
    @Test void rollbackDoesNotAdvanceOrPublish() {
        service.markChanged(2); synchronization().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verifyNoInteractions(mapper, push);
    }
    @Test void closureOutsideATransactionIsRefused() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(IllegalStateException.class, () -> service.markChanged(2));
        verifyNoInteractions(mapper, push);
    }
    @Test void anAfterCommitInnerTransactionGetsItsOwnRecipientQueue() {
        service.markChanged(9); var outer = synchronization();
        when(mapper.bumpExistingNoticeStateVersion(9)).thenReturn(1);
        outer.beforeCommit(false); outer.suspend();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        service.markChanged(2); var inner = synchronization(); assertNotSame(outer, inner);
        when(mapper.bumpExistingNoticeStateVersion(2)).thenReturn(1);
        inner.beforeCommit(false); inner.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        outer.resume(); outer.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        var order = inOrder(mapper, push);
        order.verify(mapper).bumpExistingNoticeStateVersion(9); order.verify(push).invalidated(9);
        order.verify(mapper).bumpExistingNoticeStateVersion(2); order.verify(push).invalidated(2);
    }
}
