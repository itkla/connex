package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.notifications.NotificationProperties;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @Mock private NotificationMapper notificationMapper;
    @Mock private AuthService authService;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        NotificationProperties properties = new NotificationProperties();
        properties.setMaxPageSize(100);
        service = new NotificationService(notificationMapper, authService, properties);
        User user = new User();
        user.setId(42);
        when(authService.getCurrentUser()).thenReturn(user);
    }

    @Test
    void pageUsesOffsetPaginationScopedToTheRecipientAcrossWorkspaces() {
        when(notificationMapper.findPage(42, "unread", "task", "deal", 55, 20, 40))
            .thenReturn(List.of());
        when(notificationMapper.countPage(42, "unread", "task", "deal", 55))
            .thenReturn(0L);

        service.getPage("unread", "task", "deal", 55, 3, 20);

        verify(notificationMapper).findPage(42, "unread", "task", "deal", 55, 20, 40);
        verify(notificationMapper).countPage(42, "unread", "task", "deal", 55);
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
}
