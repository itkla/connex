package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.dto.RevokedInvitationDto;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class RevokedGrantNotificationCleanupTest {

    private static final int USER_ID = 41;

    @Mock private TenantWorkScope tenantWorkScope;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private UserMapper userMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private NotificationMapper notificationMapper;
    @Mock private NotificationStateVersionService notificationStateVersionService;
    @Mock private TransactionStatus transactionStatus;

    private RevokedGrantNotificationCleanup cleanup;

    @BeforeEach
    void setUp() {
        cleanup = new RevokedGrantNotificationCleanup(tenantWorkScope, transactionTemplate, userMapper,
            workspaceMapper, notificationMapper, notificationStateVersionService);
        lenient().doAnswer(invocation -> {
            Runnable work = invocation.getArgument(1);
            work.run();
            return null;
        }).when(tenantWorkScope).inLifecycleWorkspace(anyInt(), any(Runnable.class));
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> work = invocation.getArgument(0);
            work.accept(transactionStatus);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().when(userMapper.lockById(USER_ID)).thenReturn(USER_ID);
        lenient().when(notificationMapper.lockRecipientMemberships(USER_ID)).thenReturn(List.of());
    }

    @Test
    void eachRevokedWorkspaceIsCleanedOnceInAscendingOrderAfterTheDocumentedLocks() {
        cleanup.cleanUp(USER_ID, List.of(revoked(9), revoked(4), revoked(9)));

        InOrder order = inOrder(tenantWorkScope, userMapper, workspaceMapper, notificationMapper,
            notificationStateVersionService);
        for (int workspaceId : List.of(4, 9)) {
            order.verify(tenantWorkScope).inLifecycleWorkspace(eq(workspaceId), any(Runnable.class));
            order.verify(userMapper).lockById(USER_ID);
            order.verify(workspaceMapper).lockWorkspace(workspaceId);
            order.verify(notificationMapper).lockRecipientMemberships(USER_ID);
            order.verify(notificationMapper)
                .deleteHistoricalNotificationBaselinesForRecipient(workspaceId, USER_ID);
            order.verify(notificationMapper).deleteAllForRecipient(workspaceId, USER_ID);
            order.verify(notificationStateVersionService).markChanged(USER_ID);
        }
        order.verifyNoMoreInteractions();
    }

    @Test
    void aWorkspaceWhereTheAccountHoldsAnyMembershipAgainKeepsItsNotifications() {
        when(notificationMapper.lockRecipientMemberships(USER_ID)).thenReturn(List.of(4));

        cleanup.cleanUp(USER_ID, List.of(revoked(4), revoked(9)));

        verify(notificationMapper, never()).deleteAllForRecipient(4, USER_ID);
        verify(notificationMapper, never()).deleteHistoricalNotificationBaselinesForRecipient(4, USER_ID);
        verify(notificationMapper).deleteAllForRecipient(9, USER_ID);
        verify(notificationMapper).deleteHistoricalNotificationBaselinesForRecipient(9, USER_ID);
    }

    @Test
    void aDeletedAccountLeavesNothingToClean() {
        when(userMapper.lockById(USER_ID)).thenReturn(null);

        cleanup.cleanUp(USER_ID, List.of(revoked(4)));

        verify(workspaceMapper, never()).lockWorkspace(anyInt());
        verify(notificationMapper, never()).deleteAllForRecipient(anyInt(), anyInt());
        verifyNoInteractions(notificationStateVersionService);
    }

    @Test
    void aFailingWorkspaceIsSkippedWithoutStoppingTheOthersOrTheCaller() {
        doThrow(new ServiceUnavailableException("unservable"))
            .when(tenantWorkScope).inLifecycleWorkspace(eq(4), any(Runnable.class));
        doThrow(new IllegalStateException("Deadlock found when trying to get lock"))
            .when(notificationMapper).deleteAllForRecipient(9, USER_ID);

        cleanup.cleanUp(USER_ID, List.of(revoked(4), revoked(9), revoked(12)));

        verify(notificationMapper).deleteAllForRecipient(12, USER_ID);
        verify(notificationStateVersionService).markChanged(USER_ID);
    }

    @Test
    void itRefusesToRunInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class,
                () -> cleanup.cleanUp(USER_ID, List.of(revoked(4))));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(tenantWorkScope, transactionTemplate, notificationMapper);
    }

    private static RevokedInvitationDto revoked(int workspaceId) {
        return new RevokedInvitationDto(workspaceId, 1, "Workspace " + workspaceId);
    }
}
