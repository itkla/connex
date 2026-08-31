package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;

class NotificationSourceResolutionServiceTest {

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void resolvesOnlyRecipientsWithoutRemainingActionableStepsInSortedLockOrder() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        NotificationStateVersionService stateVersions = mock(NotificationStateVersionService.class);
        DocumentApprovalService approvals = mock(DocumentApprovalService.class);
        NotificationSourceResolutionService service =
            new NotificationSourceResolutionService(mapper, stateVersions, approvals);
        when(mapper.findActiveApprovalRequestRecipientIds(7, 31))
            .thenReturn(List.of(9, 3, 6));
        when(approvals.actionableRecipientIdsForDocument(7, 31, Set.of(3, 6, 9)))
            .thenReturn(Set.of(6));
        when(mapper.resolveApprovalRequestsForRecipient(7, 31, 3, "2026-08-30 18:15:00"))
            .thenReturn(1);
        when(mapper.resolveApprovalRequestsForRecipient(7, 31, 9, "2026-08-30 18:15:00"))
            .thenReturn(1);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.resolveApprovalRequests(7, 31, Instant.parse("2026-08-30T18:15:00Z"));

        InOrder locks = inOrder(mapper);
        locks.verify(mapper).lockApprovalRequestRecipientMembership(7, 3);
        locks.verify(mapper).lockApprovalRequestRecipientMembership(7, 9);
        verify(mapper, never()).lockApprovalRequestRecipientMembership(7, 6);
        verify(mapper, never()).resolveApprovalRequestsForRecipient(
            7, 31, 6, "2026-08-30 18:15:00");
        verify(stateVersions).markChanged(3);
        verify(stateVersions).markChanged(9);
        verify(stateVersions, never()).markChanged(6);
    }

    @Test
    void terminalStateResolvesEveryRecipientAndOnlyBumpsChangedRecipients() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        NotificationStateVersionService stateVersions = mock(NotificationStateVersionService.class);
        DocumentApprovalService approvals = mock(DocumentApprovalService.class);
        NotificationSourceResolutionService service =
            new NotificationSourceResolutionService(mapper, stateVersions, approvals);
        when(mapper.findActiveApprovalRequestRecipientIds(7, 31))
            .thenReturn(List.of(8, 2));
        when(approvals.actionableRecipientIdsForDocument(7, 31, Set.of(2, 8)))
            .thenReturn(Set.of());
        when(mapper.resolveApprovalRequestsForRecipient(7, 31, 2, "2026-08-30 18:15:00"))
            .thenReturn(1);
        when(mapper.resolveApprovalRequestsForRecipient(7, 31, 8, "2026-08-30 18:15:00"))
            .thenReturn(0);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.resolveApprovalRequests(7, 31, Instant.parse("2026-08-30T18:15:00Z"));

        InOrder locks = inOrder(mapper);
        locks.verify(mapper).lockApprovalRequestRecipientMembership(7, 2);
        locks.verify(mapper).lockApprovalRequestRecipientMembership(7, 8);
        verify(stateVersions).markChanged(2);
        verify(stateVersions, never()).markChanged(8);
    }

    @Test
    void refusesToReconcileOutsideTheAuthoritativeTransaction() {
        NotificationMapper mapper = mock(NotificationMapper.class);
        NotificationSourceResolutionService service = new NotificationSourceResolutionService(
            mapper,
            mock(NotificationStateVersionService.class),
            mock(DocumentApprovalService.class));

        assertThrows(
            IllegalStateException.class,
            () -> service.resolveApprovalRequests(7, 31, Instant.parse("2026-08-30T18:15:00Z")));
        verify(mapper, never()).findActiveApprovalRequestRecipientIds(7, 31);
    }
}
