package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.NotificationCountsDto;
import ooo.klae.connex.backend.dto.NotificationDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.notifications.NotificationProperties;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @Mock private NotificationMapper notificationMapper;
    @Mock private AuthService authService;
    @Mock private NotificationStateVersionService stateVersionService;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        NotificationProperties properties = new NotificationProperties();
        properties.setMaxPageSize(100);
        service = new NotificationService(notificationMapper, authService, properties, stateVersionService);
        User user = new User();
        user.setId(42);
        when(authService.getCurrentUser()).thenReturn(user);
    }

    @Test
    void pageUsesOffsetPaginationScopedToTheRecipientAcrossWorkspaces() {
        when(notificationMapper.getDatabaseUtcTimestamp()).thenReturn("2026-06-25 00:00:00");
        when(notificationMapper.findPage(
                42, "unread", "task", "deal", 55, "2026-06-25 00:00:00", 20, 40))
            .thenReturn(List.of());
        when(notificationMapper.countPage(
                42, "unread", "task", "deal", 55, "2026-06-25 00:00:00"))
            .thenReturn(0L);
        when(notificationMapper.getStateVersion(42)).thenReturn(19L);

        var page = service.getPage("unread", "task", "deal", 55, 3, 20);

        verify(notificationMapper).findPage(
            42, "unread", "task", "deal", 55, "2026-06-25 00:00:00", 20, 40);
        verify(notificationMapper).countPage(
            42, "unread", "task", "deal", 55, "2026-06-25 00:00:00");
        assertEquals(19L, page.stateVersion());
    }

    @Test
    void zeroRowMutationIsNotFoundAfterScopedRead() {
        Notification current = new Notification();
        current.setId(99);
        when(notificationMapper.findById(42, 99)).thenReturn(current);
        when(notificationMapper.markRead(42, 99)).thenReturn(0);

        assertThrows(ResourceNotFoundException.class, () -> service.markRead(99));

        verify(notificationMapper).findById(42, 99);
        verify(notificationMapper).markRead(42, 99);
    }

    @Test
    void mutationCannotCrossRecipientScope() {
        when(notificationMapper.findById(42, 99)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> service.markRead(99));

        verify(notificationMapper, never()).markRead(42, 99);
    }

    @Test
    void markAllReadBindsOneDatabaseTimestampAndReturnsTheStateVersion() {
        NotificationCountsDto counts = new NotificationCountsDto();
        counts.setStateVersion(91L);
        when(notificationMapper.lockRecipientMemberships(42)).thenReturn(List.of(7));
        when(notificationMapper.getInboxCutoffId(42)).thenReturn(81L);
        when(notificationMapper.getDatabaseUtcTimestamp()).thenReturn("2026-06-25 00:00:00");
        when(notificationMapper.markAllRead(42, 81L, "2026-06-25 00:00:00")).thenReturn(2);
        when(notificationMapper.getUnreadCounts(42, "2026-06-25 00:00:00")).thenReturn(counts);

        NotificationCountsDto result = service.markAllRead();

        InOrder mutationOrder = inOrder(notificationMapper);
        mutationOrder.verify(notificationMapper).lockRecipientMemberships(42);
        mutationOrder.verify(notificationMapper).getDatabaseUtcTimestamp();
        mutationOrder.verify(notificationMapper).getInboxCutoffId(42);
        mutationOrder.verify(notificationMapper).markAllRead(42, 81L, "2026-06-25 00:00:00");
        mutationOrder.verify(notificationMapper).getUnreadCounts(42, "2026-06-25 00:00:00");
        assertEquals(81L, result.getCutoffId());
        assertEquals("2026-06-25 00:00:00", result.getReadAt());
        assertEquals(91L, result.getStateVersion());
        assertSame(counts, result);
        verify(stateVersionService).bumpNow(42);
    }

    @Test
    void mutationResponseCarriesTheCurrentStateVersion() {
        Notification current = new Notification();
        current.setId(99);
        current.setReadAt("2026-06-25 00:00:00");
        Notification updated = new Notification();
        updated.setId(99);
        when(notificationMapper.findById(42, 99)).thenReturn(current, updated);
        when(notificationMapper.markUnread(42, 99)).thenReturn(1);
        when(stateVersionService.bumpNow(42)).thenReturn(92L);

        NotificationDto result = service.markUnread(99);

        assertEquals(92L, result.getStateVersion());
        verify(stateVersionService).bumpNow(42);
    }

    @Test
    void idempotentMutationDoesNotAdvanceStateVersion() {
        Notification current = new Notification();
        current.setId(99);
        current.setReadAt("2026-06-25 00:00:00");
        when(notificationMapper.findById(42, 99)).thenReturn(current);
        when(notificationMapper.getStateVersion(42)).thenReturn(17L);

        NotificationDto result = service.markRead(99);

        assertEquals(17L, result.getStateVersion());
        verify(stateVersionService, never()).bumpNow(42);
    }
}
